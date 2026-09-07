package com.daf360.rh.service.photo;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The on-disk copy of an employee's profile photo, under
 * {@code {STORAGE_PATH}/profiles/{profileId}/}.
 *
 * <p>Extracted from {@code EmployeeProfileService} because that cache had quietly stopped
 * being a cache: it was written once on the first request that happened to miss, then read
 * forever without ever being compared against SharePoint again. Replacing the photo in
 * SharePoint had no observable effect, which is not a cache behaviour — it is storage
 * pretending to be one.
 *
 * <p>Three things make it a cache again:
 * <ul>
 *   <li>the cached file's modification time is set to the SharePoint file's, so the two can
 *       be compared without storing a timestamp anywhere;</li>
 *   <li>a {@code .checked} marker records the last revalidation, so the comparison costs a
 *       Graph call once a day rather than once an avatar;</li>
 *   <li>images are shrunk on the way in — the tree holds 5-6.5 MB originals, and serving
 *       those verbatim as 40px avatars is the single largest waste on the page.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfilePhotoCache {

    /** Longest edge kept on a cached avatar. Generous for a profile header, ~1% of the
     *  bytes of the originals HR uploads. */
    private static final int MAX_EDGE = 512;

    /** Longest edge of the list variant. The grid card renders a 112px circle and the annuaire
     *  a 32px one, so 512px is 4-16x more pixels than any list surface can show — and twelve
     *  of them is the bulk of what the profiles page downloads. 128 covers both at 2x DPR. */
    private static final int SMALL_EDGE = 128;

    /** Subdirectory holding the list variant. A SUBDIRECTORY rather than a name prefix because
     *  {@link #read} serves the newest photo in the profile directory: a thumbnail sitting
     *  beside the master would win that comparison half the time and silently downgrade the
     *  detail page. {@code Files.list} does not recurse, so the two cannot collide. */
    private static final String SMALL_DIR = "sm";

    /** How often the cached copy is compared against SharePoint. A day: a photo corrected in
     *  SharePoint appears the same working day, and an unchanged one costs one call. */
    private static final Duration REVALIDATE_AFTER = Duration.ofHours(24);

    /** Extensions we recognise as a cached photo. Anything else in the directory — notably
     *  the {@code .checked} marker — is not a candidate to serve. */
    private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    private static final String CHECK_MARKER = ".checked";

    /**
     * Records WHOSE photo the cached bytes are, so a directory cannot outlive the person it was
     * filled for.
     *
     * <p>The bug this exists to prevent, observed on the test server (2026-09-07): the cache is
     * keyed on {@code employee_profiles.id}, an SSIS reload re-assigned every id from 1, and the
     * directories — which live on the container's volume, not in the database — kept the previous
     * mapping. So {@code profiles/68/} still held the JPEG of whoever used to be id 68, and
     * {@link #read} served it happily. Every database-side check came back clean, because the
     * poisoned state was not in the database.
     *
     * <p>A foreign key cannot help here and neither can a cascade: nothing in SQL Server can
     * reach a filesystem. The only defence is for the cache to carry its own proof of identity
     * and check it on read — which also means no operator step, and no post-reload hook, is
     * needed for it to recover.
     *
     * <p>Content is {@code {userId}|{normalized fullName}} — both halves, because a reload can
     * re-point {@code user_id} without changing the name, and can change a name without moving
     * the id. Either alone leaves a hole.
     */
    private static final String IDENTITY_MARKER = ".identity";

    private final AppProperties appProperties;

    public Path directory(Long profileId) {
        return Paths.get(appProperties.getStoragePath(), "profiles", String.valueOf(profileId));
    }

    /**
     * The cached bytes for an identity, or empty when nothing usable is cached FOR THAT IDENTITY.
     *
     * <p>{@code identity} is the caller's proof of who this profile currently belongs to. A
     * directory whose {@link #IDENTITY_MARKER} disagrees is treated as a MISS, not as a hit —
     * the bytes belong to whoever held this id before, and serving them is how one employee's
     * face ends up on another's record.
     *
     * <p>Passing null skips the check, for callers that genuinely have no identity to offer.
     * That is the pre-existing behaviour and stays available deliberately: a missing identity
     * must not blank an avatar, it just cannot be verified.
     *
     * <p>Never throws.
     */
    public Optional<byte[]> read(Long profileId, String identity) {
        if (!identityMatches(profileId, identity)) return Optional.empty();
        try {
            Path file = newestPhoto(directory(profileId));
            return file == null ? Optional.empty() : Optional.of(Files.readAllBytes(file));
        } catch (Exception e) {
            log.warn("Cache photo illisible pour le profil {}: {}", profileId, e.getMessage());
            return Optional.empty();
        }
    }

    /** @deprecated unverified read — kept for callers with no identity in hand. Prefer
     *  {@link #read(Long, String)}: an unverified hit is exactly how a re-assigned profile id
     *  serves the previous occupant's photo. */
    @Deprecated
    public Optional<byte[]> read(Long profileId) {
        return read(profileId, null);
    }

    /**
     * The list variant, falling back to the full-size copy when there is none.
     *
     * <p>The fallback is what makes this safe to deploy over an existing cache: entries written
     * before {@link #SMALL_DIR} existed have no thumbnail, and the alternative to serving their
     * master copy is a blank avatar until something happens to rewrite them. A warmup pass or
     * the next revalidation fills the variant in.
     */
    public Optional<byte[]> readSmall(Long profileId, String identity) {
        if (!identityMatches(profileId, identity)) return Optional.empty();
        try {
            Path file = newestPhoto(directory(profileId).resolve(SMALL_DIR));
            if (file != null) return Optional.of(Files.readAllBytes(file));
        } catch (Exception e) {
            log.debug("Vignette illisible pour le profil {}: {}", profileId, e.getMessage());
        }
        return read(profileId, identity);
    }

    /** @deprecated see {@link #read(Long)}. */
    @Deprecated
    public Optional<byte[]> readSmall(Long profileId) {
        return readSmall(profileId, null);
    }

    /**
     * Whether the cached directory was filled for this identity.
     *
     * <p>Three outcomes, and the middle one is the important one:
     * <ul>
     *   <li>marker matches → true, serve the cache;</li>
     *   <li>marker DISAGREES → false, and the directory is cleared on the spot. Leaving it would
     *       mean re-reading, re-comparing and re-rejecting on every avatar render, and the bytes
     *       are known to belong to someone else — there is nothing to keep;</li>
     *   <li>no marker at all → true. Entries written before this marker existed are unverifiable,
     *       not wrong, and blanking every avatar on deploy day to prove a point would be its own
     *       outage. They gain a marker the next time they are written.</li>
     * </ul>
     */
    private boolean identityMatches(Long profileId, String identity) {
        if (identity == null || identity.isBlank()) return true;   // nothing to verify against
        try {
            Path marker = directory(profileId).resolve(IDENTITY_MARKER);
            if (!Files.exists(marker)) return true;                // pre-marker entry
            String stored = Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (stored.isEmpty() || stored.equals(identity.trim())) return true;

            log.warn("Cache photo du profil {} rejete : rempli pour '{}', demande pour '{}' — "
                     + "l'identite derriere cet id a change (rechargement de donnees ?), cache vide",
                    profileId, stored, identity);
            clear(profileId);
            return false;
        } catch (Exception e) {
            // Unreadable marker must not blank the avatar: degrade to the previous behaviour.
            log.debug("Marqueur d'identite illisible pour le profil {}: {}", profileId, e.getMessage());
            return true;
        }
    }

    /**
     * An HTTP entity tag for the cached photo, or empty when nothing is cached.
     *
     * <p>Built from the cached file's modification time and size — both already on disk, so this
     * costs a stat and never reads the bytes. The mtime is the SharePoint timestamp
     * ({@link #write} stamps it), so the tag changes exactly when the image does and is identical
     * for every user: two browsers asking about the same photo get the same tag.
     *
     * <p>Weak ({@code W/}) because the bytes are re-encoded on the way in — the shrink is not
     * guaranteed byte-stable across JDK versions, and a strong tag would be a promise about
     * octets rather than about the image.
     *
     * @param small the list variant, which has its own file and therefore its own tag — serving
     *              one variant's tag for the other would let a 128px copy satisfy a request for
     *              the 512px one
     */
    public Optional<String> etag(Long profileId, boolean small) {
        try {
            Path dir = small ? directory(profileId).resolve(SMALL_DIR) : directory(profileId);
            Path file = newestPhoto(dir);
            // No thumbnail on disk means readSmall() falls back to the master, so the tag has
            // to follow the file that will actually be served — otherwise a 304 would be
            // answered against a tag for a file the client never received.
            if (file == null && small) file = newestPhoto(directory(profileId));
            if (file == null) return Optional.empty();
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size  = Files.size(file);
            return Optional.of("W/\"" + mtime + "-" + size + "\"");
        } catch (Exception e) {
            // No tag means the caller sends no ETag and the response is simply unconditional.
            log.debug("ETag photo indisponible pour le profil {}: {}", profileId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether a photo is cached, without reading it. For the warmup pass, which asks this
     *  once per employee and must not pull ~100 files off disk to answer it. */
    public boolean has(Long profileId) {
        try {
            return newestPhoto(directory(profileId)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * When the SharePoint copy behind the cached file was last modified — i.e. the cached
     * file's own mtime, which {@link #write} sets from the remote timestamp.
     *
     * <p>Empty for a file written before this class existed, which is treated as "unknown, so
     * revalidate": the safe direction, since the alternative is keeping a stale photo forever.
     */
    public Optional<Instant> cachedRemoteTime(Long profileId) {
        try {
            Path file = newestPhoto(directory(profileId));
            if (file == null) return Optional.empty();
            return Optional.of(Files.getLastModifiedTime(file).toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Whether it is time to ask SharePoint whether the cached copy is still current. */
    public boolean needsRevalidation(Long profileId) {
        Path marker = directory(profileId).resolve(CHECK_MARKER);
        try {
            if (!Files.exists(marker)) return true;
            Instant last = Files.getLastModifiedTime(marker).toInstant();
            return Duration.between(last, Instant.now()).compareTo(REVALIDATE_AFTER) >= 0;
        } catch (Exception e) {
            return true; // cannot tell — check, rather than serve something stale forever
        }
    }

    /**
     * Records that a revalidation happened, whatever its outcome.
     *
     * <p>Called even when the remote copy turned out to be unchanged, and even when the check
     * failed: otherwise an employee whose folder is unreachable would re-attempt on every
     * single render, which is the exact cost this cache exists to avoid.
     */
    public void markChecked(Long profileId) {
        try {
            Path dir = directory(profileId);
            Files.createDirectories(dir);
            Path marker = dir.resolve(CHECK_MARKER);
            if (Files.exists(marker)) {
                Files.setLastModifiedTime(marker, FileTime.from(Instant.now()));
            } else {
                Files.createFile(marker);
            }
        } catch (IOException e) {
            log.debug("Marqueur de revalidation non ecrit pour le profil {}: {}",
                    profileId, e.getMessage());
        }
    }

    /**
     * Replaces the cached photo.
     *
     * @param remoteFileName    the SharePoint name, used only for its extension
     * @param remoteLastModified the SharePoint timestamp, stamped onto the local file so a
     *                           later revalidation can compare the two. Null for a photo
     *                           uploaded through the app, which stamps "now".
     * @return the bytes actually cached — shrunk when possible, the input unchanged otherwise
     */
    public byte[] write(Long profileId, String remoteFileName, String remoteLastModified,
                        byte[] content) {
        return write(profileId, remoteFileName, remoteLastModified, content, null);
    }

    /**
     * As {@link #write}, recording WHOSE photo these bytes are.
     *
     * <p>{@code identity} is stamped into {@link #IDENTITY_MARKER} and checked by every later
     * read. Without it the directory is unverifiable and a re-assigned profile id will serve it
     * to the wrong person — so every caller that knows the identity should pass it.
     */
    public byte[] write(Long profileId, String remoteFileName, String remoteLastModified,
                        byte[] content, String identity) {
        byte[] stored = shrink(content, remoteFileName);
        try {
            Path dir = directory(profileId);
            Files.createDirectories(dir);
            writeIdentity(dir, identity);
            // Old entries must go: read() serves the newest file, so leaving them would keep
            // a superseded photo one mtime away from being served again.
            deletePhotos(dir);

            String ext = extensionOf(remoteFileName);
            Path target = dir.resolve(UUID.randomUUID() + ext);
            Files.write(target, stored);

            Instant stamp = parseGraphTime(remoteLastModified).orElse(Instant.now());
            try {
                Files.setLastModifiedTime(target, FileTime.from(stamp));
            } catch (IOException mtimeUnsupported) {
                log.debug("Horodatage non applique sur {}: {}", target, mtimeUnsupported.getMessage());
            }
            writeSmall(dir, stored, remoteFileName, stamp);
            markChecked(profileId);
        } catch (IOException e) {
            // Best-effort: the caller already holds the bytes and can serve them regardless.
            log.warn("Cache photo non ecrit pour le profil {}: {}", profileId, e.getMessage());
        }
        return stored;
    }

    /**
     * Stamps the identity these bytes belong to. Silent when the caller offered none — the
     * directory is then simply unverifiable, exactly as before this marker existed.
     */
    private void writeIdentity(Path profileDir, String identity) {
        if (identity == null || identity.isBlank()) return;
        try {
            Files.writeString(profileDir.resolve(IDENTITY_MARKER), identity.trim(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Not fatal, but it does leave this entry unverifiable — worth a line, unlike the
            // other best-effort writes here.
            log.warn("Marqueur d'identite non ecrit pour {}: {}", profileDir, e.getMessage());
        }
    }

    /** Forgets the cached photo, so the next request rediscovers it. */
    public void clear(Long profileId) {
        try {
            Path dir = directory(profileId);
            if (!Files.isDirectory(dir)) return;
            deletePhotos(dir);
            // The identity marker goes too. Leaving it would describe bytes that no longer
            // exist, and the next write may be for a different person entirely.
            Files.deleteIfExists(dir.resolve(IDENTITY_MARKER));
            // The variant too, or readSmall keeps serving the old face from sm/ after the
            // master is gone — a cleared cache that still shows the previous photo.
            deletePhotos(dir.resolve(SMALL_DIR));
            Files.deleteIfExists(dir.resolve(CHECK_MARKER));
        } catch (IOException e) {
            log.warn("Cache photo non vide pour le profil {}: {}", profileId, e.getMessage());
        }
    }

    /**
     * Whether the cached directory carries a marker CONFIRMING this identity.
     *
     * <p>Stricter than {@link #read}'s check, and deliberately so: read tolerates a missing
     * marker (a pre-marker entry is unverifiable, not wrong, and blanking it would be its own
     * outage), whereas a caller deciding whether to trust a timestamp comparison needs to know
     * the difference between "confirmed as this person" and "cannot tell".
     *
     * @return true only when a marker exists AND matches
     */
    public boolean hasIdentity(Long profileId, String identity) {
        if (identity == null || identity.isBlank()) return false;
        try {
            Path marker = directory(profileId).resolve(IDENTITY_MARKER);
            if (!Files.exists(marker)) return false;
            return Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8)
                    .trim().equals(identity.trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether a remote timestamp is newer than what is cached. Unknown either side → yes. */
    public boolean remoteIsNewer(Long profileId, String remoteLastModified) {
        Optional<Instant> remote = parseGraphTime(remoteLastModified);
        Optional<Instant> local  = cachedRemoteTime(profileId);
        if (remote.isEmpty() || local.isEmpty()) return true;
        return remote.get().isAfter(local.get());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Path newestPhoto(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(ProfilePhotoCache::isPhoto)
                    .max(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .orElse(null);
        }
    }

    /**
     * Writes the list variant beside the master, best-effort.
     *
     * <p>Silent when {@link #shrink} could not do better than its input — it returns the very
     * same array in that case (a WebP, or an image already smaller than the target), and a
     * byte-identical second copy would cost disk for nothing. {@link #readSmall} falls back to
     * the master copy, so "no thumbnail" degrades to "slightly larger download", never to a
     * missing avatar. Never throws: the master photo is already written and servable.
     */
    private void writeSmall(Path profileDir, byte[] stored, String remoteFileName, Instant stamp) {
        byte[] small = shrink(stored, remoteFileName, SMALL_EDGE);
        if (small == stored) return;
        try {
            Path dir = profileDir.resolve(SMALL_DIR);
            Files.createDirectories(dir);
            deletePhotos(dir);
            Path target = dir.resolve(UUID.randomUUID() + extensionOf(remoteFileName));
            Files.write(target, small);
            try {
                Files.setLastModifiedTime(target, FileTime.from(stamp));
            } catch (IOException mtimeUnsupported) {
                log.debug("Horodatage non applique sur la vignette {}: {}",
                        target, mtimeUnsupported.getMessage());
            }
        } catch (IOException e) {
            log.debug("Vignette non ecrite sous {}: {}", profileDir, e.getMessage());
        }
    }

    private void deletePhotos(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.filter(ProfilePhotoCache::isPhoto).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isPhoto(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return ".jpg";
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().filter(lower::endsWith).findFirst().orElse(".jpg");
    }

    private static Optional<Instant> parseGraphTime(String iso) {
        if (iso == null || iso.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(iso));
        } catch (Exception notIso) {
            return Optional.empty();
        }
    }

    /**
     * Scales the longest edge down to {@link #MAX_EDGE}, keeping the source format.
     *
     * <p>Returns the input untouched whenever it cannot do better, and that is the important
     * case rather than the edge case: ImageIO ships no WebP reader, so {@code .webp} decodes
     * to null. Serving a 5 MB original is merely wasteful; serving nothing because the resize
     * failed would blank the avatar, so every failure path here keeps the original bytes.
     */
    private byte[] shrink(byte[] content, String fileName) {
        return shrink(content, fileName, MAX_EDGE);
    }

    /** As {@link #shrink(byte[], String)}, to an explicit longest edge — {@link #SMALL_EDGE}
     *  for the list variant. Same contract: the input array is returned, by reference, whenever
     *  it cannot do better, which is how callers detect "no variant worth storing". */
    private byte[] shrink(byte[] content, String fileName, int maxEdge) {
        if (content == null || content.length == 0) return content;
        String ext = extensionOf(fileName);
        if (ext.equals(".webp")) return content; // no reader in the JDK

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content));
            if (source == null) return content;

            int longest = Math.max(source.getWidth(), source.getHeight());
            if (longest <= maxEdge) return content;

            double scale = (double) maxEdge / longest;
            int w = Math.max(1, (int) Math.round(source.getWidth()  * scale));
            int h = Math.max(1, (int) Math.round(source.getHeight() * scale));

            // TYPE_INT_RGB, not the source type: an indexed or grayscale source scales badly
            // in its own colour model, and a portrait has no transparency worth keeping.
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(source, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }

            String format = ext.equals(".png") ? "png" : "jpg";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, format, out) || out.size() == 0) return content;

            // A "shrunk" file that grew is not an improvement — PNG re-encoding of a
            // photographic source routinely does this.
            byte[] result = out.toByteArray();
            return result.length < content.length ? result : content;

        } catch (Exception e) {
            log.debug("Redimensionnement impossible pour {} ({}), original conserve",
                    fileName, e.getMessage());
            return content;
        }
    }
}
