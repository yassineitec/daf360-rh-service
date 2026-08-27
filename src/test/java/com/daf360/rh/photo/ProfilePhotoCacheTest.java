package com.daf360.rh.photo;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.service.photo.ProfilePhotoCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local photo cache.
 *
 * <p>Its predecessor was a cache in name only: written once, then read forever without ever
 * being compared against SharePoint, so a photo replaced upstream never appeared. The tests
 * below pin the three properties that make it a cache again — a comparable timestamp, a
 * rate-limited revalidation marker, and shrinking on the way in.
 */
class ProfilePhotoCacheTest {

    private static final Long PROFILE = 42L;

    @TempDir Path tempDir;

    private ProfilePhotoCache cache;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setStoragePath(tempDir.toString());
        cache = new ProfilePhotoCache(props);
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            // A gradient rather than a flat fill: a single colour compresses to almost
            // nothing, which would make the "did it get smaller" assertions meaningless.
            for (int x = 0; x < width; x += 8) {
                g.setColor(new Color(x % 256, (x * 3) % 256, (x * 7) % 256));
                g.fillRect(x, 0, 8, height);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    // ── Read / write ──────────────────────────────────────────────────────────

    @Test
    void readsBackWhatItWrote() throws Exception {
        byte[] small = jpeg(64, 64);

        cache.write(PROFILE, "Photo.jpg", null, small);

        assertThat(cache.read(PROFILE)).isPresent();
    }

    @Test
    void readIsEmptyWhenNothingIsCached() {
        assertThat(cache.read(PROFILE)).isEmpty();
    }

    /** The marker file lives in the same directory and must never be served as an image. */
    @Test
    void neverServesTheRevalidationMarkerAsAPhoto() {
        cache.markChecked(PROFILE);

        assertThat(cache.read(PROFILE)).isEmpty();
    }

    /** A replacement must remove the previous file, not sit beside it — read() serves the
     *  newest, so a leftover is one timestamp away from coming back. */
    @Test
    void replacesRatherThanAccumulates() throws Exception {
        cache.write(PROFILE, "Photo.jpg", null, jpeg(64, 64));
        cache.write(PROFILE, "Photo.png", null, jpeg(64, 64));

        try (Stream<Path> files = Files.list(cache.directory(PROFILE))) {
            assertThat(files.filter(p -> !p.getFileName().toString().startsWith("."))).hasSize(1);
        }
    }

    @Test
    void clearForgetsEverythingIncludingTheMarker() throws Exception {
        cache.write(PROFILE, "Photo.jpg", null, jpeg(64, 64));

        cache.clear(PROFILE);

        assertThat(cache.read(PROFILE)).isEmpty();
        assertThat(cache.needsRevalidation(PROFILE)).isTrue();
    }

    // ── Shrinking ─────────────────────────────────────────────────────────────

    /**
     * The live tree holds 5-6.5 MB originals and they were served verbatim as 40px avatars.
     * A 2000px source must come back smaller, and still be a decodable image.
     */
    @Test
    void shrinksAnOversizedImageAndKeepsItDecodable() throws Exception {
        byte[] big = jpeg(2000, 1500);

        byte[] stored = cache.write(PROFILE, "Photo.jpg", null, big);

        assertThat(stored.length).isLessThan(big.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(decoded).isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isLessThanOrEqualTo(512);
        // Aspect ratio preserved — a stretched avatar is worse than a large one.
        assertThat((double) decoded.getWidth() / decoded.getHeight())
                .isCloseTo(2000d / 1500d, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void leavesAnAlreadySmallImageAlone() throws Exception {
        byte[] small = jpeg(200, 200);

        assertThat(cache.write(PROFILE, "Photo.jpg", null, small)).isEqualTo(small);
    }

    /**
     * ImageIO ships no WebP reader, so a .webp decodes to null. Serving a large original is
     * merely wasteful; failing the resize and caching nothing would blank the avatar, so the
     * bytes must survive untouched.
     */
    @Test
    void keepsTheOriginalWhenTheFormatCannotBeDecoded() {
        byte[] notReallyAnImage = new byte[]{1, 2, 3, 4, 5};

        assertThat(cache.write(PROFILE, "Photo.webp", null, notReallyAnImage))
                .isEqualTo(notReallyAnImage);
        // get() then isEqualTo: OptionalAssert.contains uses equals(), and byte[] equality is
        // identity — read() hands back a fresh array, so contains() would always fail here.
        assertThat(cache.read(PROFILE)).isPresent();
        assertThat(cache.read(PROFILE).get()).isEqualTo(notReallyAnImage);
    }

    // ── Revalidation ──────────────────────────────────────────────────────────

    @Test
    void needsRevalidationWhenNothingHasEverBeenChecked() {
        assertThat(cache.needsRevalidation(PROFILE)).isTrue();
    }

    @Test
    void doesNotNeedRevalidationRightAfterAWrite() throws Exception {
        cache.write(PROFILE, "Photo.jpg", null, jpeg(64, 64));

        assertThat(cache.needsRevalidation(PROFILE)).isFalse();
    }

    @Test
    void needsRevalidationOnceTheWindowHasElapsed() throws Exception {
        cache.write(PROFILE, "Photo.jpg", null, jpeg(64, 64));
        Path marker = cache.directory(PROFILE).resolve(".checked");
        Files.setLastModifiedTime(marker, FileTime.from(Instant.now().minusSeconds(60 * 60 * 25)));

        assertThat(cache.needsRevalidation(PROFILE)).isTrue();
    }

    /**
     * The remote timestamp is stamped onto the local file, which is what lets the two be
     * compared later without storing a timestamp anywhere.
     */
    @Test
    void stampsTheRemoteTimestampOntoTheCachedFile() throws Exception {
        cache.write(PROFILE, "Photo.jpg", "2026-08-01T10:00:00Z", jpeg(64, 64));

        assertThat(cache.cachedRemoteTime(PROFILE))
                .contains(Instant.parse("2026-08-01T10:00:00Z"));
    }

    @Test
    void remoteIsNewerComparesAgainstTheStampedTime() throws Exception {
        cache.write(PROFILE, "Photo.jpg", "2026-08-01T10:00:00Z", jpeg(64, 64));

        assertThat(cache.remoteIsNewer(PROFILE, "2026-08-25T10:00:00Z")).isTrue();
        assertThat(cache.remoteIsNewer(PROFILE, "2026-08-01T10:00:00Z")).isFalse();
        assertThat(cache.remoteIsNewer(PROFILE, "2026-07-01T10:00:00Z")).isFalse();
    }

    /**
     * An unknown timestamp on either side resolves to "refresh". A file cached before this
     * class existed carries no remote stamp, and keeping such a photo forever is the exact
     * behaviour being fixed.
     */
    @Test
    void treatsAnUnknownTimestampAsWorthRefreshing() throws Exception {
        cache.write(PROFILE, "Photo.jpg", "2026-08-01T10:00:00Z", jpeg(64, 64));

        assertThat(cache.remoteIsNewer(PROFILE, null)).isTrue();
        assertThat(cache.remoteIsNewer(PROFILE, "not-a-timestamp")).isTrue();
        assertThat(cache.remoteIsNewer(999L, "2026-08-01T10:00:00Z")).isTrue();
    }
}
