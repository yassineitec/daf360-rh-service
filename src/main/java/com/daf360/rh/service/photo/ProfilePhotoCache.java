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
        if (content == null || content.length == 0) return content;
        String ext = extensionOf(fileName);
        if (ext.equals(".webp")) return content; // no reader in the JDK

        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(content));
            if (source == null) return content;

            int longest = Math.max(source.getWidth(), source.getHeight());
            if (longest <= MAX_EDGE) return content;

            double scale = (double) MAX_EDGE / longest;
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
