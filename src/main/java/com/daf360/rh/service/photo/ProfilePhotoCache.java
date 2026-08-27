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

    private final AppProperties appProperties;

    public Path directory(Long profileId) {
        return Paths.get(appProperties.getStoragePath(), "profiles", String.valueOf(profileId));
    }

    /** The cached bytes, or empty when nothing usable is cached. Never throws. */
    public Optional<byte[]> read(Long profileId) {
        try {
            Path file = newestPhoto(directory(profileId));
            return file == null ? Optional.empty() : Optional.of(Files.readAllBytes(file));
        } catch (Exception e) {
            log.warn("Cache photo illisible pour le profil {}: {}", profileId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The list variant, falling back to the full-size copy when there is none.
     *
     * <p>The fallback is what makes this safe to deploy over an existing cache: entries written
     * before {@link #SMALL_DIR} existed have no thumbnail, and the alternative to serving their
     * master copy is a blank avatar until something happens to rewrite them. A warmup pass or
     * the next revalidation fills the variant in.
     */
    public Optional<byte[]> readSmall(Long profileId) {
        try {
            Path file = newestPhoto(directory(profileId).resolve(SMALL_DIR));
            if (file != null) return Optional.of(Files.readAllBytes(file));
        } catch (Exception e) {
            log.debug("Vignette illisible pour le profil {}: {}", profileId, e.getMessage());
        }
        return read(profileId);
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
        byte[] stored = shrink(content, remoteFileName);
        try {
            Path dir = directory(profileId);
            Files.createDirectories(dir);
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

    /** Forgets the cached photo, so the next request rediscovers it. */
    public void clear(Long profileId) {
        try {
            Path dir = directory(profileId);
            if (!Files.isDirectory(dir)) return;
            deletePhotos(dir);
            // The variant too, or readSmall keeps serving the old face from sm/ after the
            // master is gone — a cleared cache that still shows the previous photo.
            deletePhotos(dir.resolve(SMALL_DIR));
            Files.deleteIfExists(dir.resolve(CHECK_MARKER));
        } catch (IOException e) {
            log.warn("Cache photo non vide pour le profil {}: {}", profileId, e.getMessage());
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
