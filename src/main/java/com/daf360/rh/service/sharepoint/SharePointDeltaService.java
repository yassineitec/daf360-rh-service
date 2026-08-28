package com.daf360.rh.service.sharepoint;

import com.daf360.rh.service.EmployeeProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the local photo cache in step with SharePoint, using one Graph call for the whole drive.
 *
 * <p><b>The problem.</b> HR replaces photos by dropping files straight into the tree, and that
 * change was invisible to the app for up to 24 hours — the {@code .checked} revalidation window.
 * Closing that by checking on every page view costs 2 Graph calls per employee shown: 200 for a
 * hundred-person annuaire, per view, which earns a 429 and seconds of latency.
 *
 * <p><b>The inversion.</b> Graph's delta API answers for the entire drive in ONE request: what
 * changed since the last token, whether that is five files or none, for ten employees or ten
 * thousand. So instead of every render asking "is my copy stale?", the server is told what moved
 * and clears exactly those entries. Freshness stops being a function of headcount.
 *
 * <p><b>Rate limited globally, and never on the request path.</b> {@link #syncIfStale()} is called
 * from list and profile endpoints, so a burst of page loads would otherwise be a burst of Graph
 * calls. One sync at a time ({@link #running}), at most one per {@link #minInterval}, and always
 * on a background thread — no HTTP request ever waits for SharePoint. The page that triggers a
 * sync does not benefit from it; the next one, seconds later, does.
 *
 * <p><b>Degrades to the old behaviour.</b> Every failure path leaves the caches alone, and the
 * 24-hour marker still works. A broken delta sync is a loss of freshness, never a loss of avatars
 * — which is also why it can be switched off with {@code app.sharepoint.delta-sync-seconds=0}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointDeltaService {

    /** The only scope today. The payroll tree will want its own cursor; see V88's header. */
    private static final String SCOPE = "HR_DRIVE";

    /**
     * How stale the local view may get before a page load triggers a sync. Zero disables the
     * whole mechanism, leaving only the 24h marker.
     */
    @Value("${app.sharepoint.delta-sync-seconds:30}")
    private long minIntervalSeconds;

    private final JdbcTemplate jdbc;
    private final GraphSharePointService graph;
    private final EmployeeProfileService profileService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>(Instant.EPOCH);

    private Duration minInterval() {
        return Duration.ofSeconds(Math.max(0, minIntervalSeconds));
    }

    /**
     * Syncs if the interval has elapsed, in the background. Returns immediately, always.
     *
     * <p>Safe to call from any read endpoint: the guard is checked before any work, so the common
     * case is two atomic reads.
     */
    public void syncIfStale() {
        if (minIntervalSeconds <= 0) return;
        Instant last = lastAttempt.get();
        if (Duration.between(last, Instant.now()).compareTo(minInterval()) < 0) return;
        // compareAndSet on the timestamp as well as the flag: two threads passing the check above
        // in the same millisecond must not both start a sync.
        if (!lastAttempt.compareAndSet(last, Instant.now())) return;
        if (!running.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            try {
                syncNow();
            } finally {
                running.set(false);
            }
        }, "sharepoint-delta");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Runs one delta pass synchronously. Exposed for an admin action and for tests.
     *
     * @return how many employees had their photo refreshed
     */
    public int syncNow() {
        String storedLink = loadDeltaLink();
        boolean baseline = storedLink == null;

        var result = graph.delta(storedLink);
        if (result.isEmpty()) {
            // Includes the expired-token case. Dropping the link forces the next run to
            // re-baseline rather than retrying a URL Graph has already refused.
            if (storedLink != null) saveDeltaLink(null, null, 0, "delta failed or token expired");
            return 0;
        }

        List<GraphSharePointService.DeltaChange> changes = result.get().changes();

        if (baseline) {
            // First run walks the entire drive. Treating that as "everything changed" would
            // invalidate every cached photo at once — thousands of Graph downloads for no reason.
            // The baseline is recorded and nothing is touched.
            saveDeltaLink(result.get().nextDeltaLink(), Instant.now(), changes.size(), null);
            log.info("Delta SharePoint initialise ({} items enumeres, aucun cache touche)",
                    changes.size());
            return 0;
        }

        Set<Long> affected = affectedProfiles(changes);
        int refreshed = 0;
        for (Long profileId : affected) {
            if (profileService.refreshPhotoFromSharePoint(profileId)) refreshed++;
        }
        saveDeltaLink(result.get().nextDeltaLink(), Instant.now(), changes.size(), null);

        if (!changes.isEmpty()) {
            log.info("Delta SharePoint : {} items changes, {} profils concernes, {} photos rechargees",
                    changes.size(), affected.size(), refreshed);
        }
        return refreshed;
    }

    /**
     * Which employees a set of changed paths belongs to.
     *
     * <p>Matching is on the employee FOLDER SEGMENT — the "Prénom NOM" directory — appearing as a
     * whole path component of the changed item's parent path. Segment-wise rather than
     * {@code contains}: a substring match would attribute a change under "Ali Jomni" to a
     * hypothetical "Ali Jomni Ep. X", and attributing one employee's photo to another is the one
     * failure this whole package refuses to risk.
     *
     * <p>Only rows that already resolved are considered. An employee whose folder has never been
     * resolved has no cache to invalidate, so a change under it is nothing to react to — the
     * normal lazy path will read it on first view.
     */
    private Set<Long> affectedProfiles(List<GraphSharePointService.DeltaChange> changes) {
        if (changes.isEmpty()) return Set.of();

        Map<String, Long> byFolder = new java.util.HashMap<>();
        try {
            jdbc.query(
                    "SELECT employee_profile_id, folder_segment FROM [dbo].[employee_sharepoint_folders] " +
                    "WHERE doc_kind = 'PHOTO' AND folder_segment IS NOT NULL",
                    rs -> {
                        byFolder.put(rs.getString("folder_segment").toLowerCase(Locale.ROOT),
                                rs.getLong("employee_profile_id"));
                    });
        } catch (Exception e) {
            log.debug("Table employee_sharepoint_folders illisible ({}) — delta sans effet",
                    e.getMessage());
            return Set.of();
        }
        if (byFolder.isEmpty()) return Set.of();

        Set<Long> affected = new HashSet<>();
        for (GraphSharePointService.DeltaChange change : changes) {
            // A changed FOLDER carries no photo itself; its files arrive as their own changes.
            if (change.isFolder() || change.parentPath() == null) continue;
            for (String segment : change.parentPath().split("/")) {
                Long profileId = byFolder.get(decodeSegment(segment).toLowerCase(Locale.ROOT));
                if (profileId != null) {
                    affected.add(profileId);
                    break;
                }
            }
        }
        return affected;
    }

    /** Graph percent-encodes spaces and accents in {@code parentReference.path}. */
    private String decodeSegment(String segment) {
        try {
            return java.net.URLDecoder.decode(segment, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception notEncoded) {
            return segment;
        }
    }

    // ── Cursor persistence ────────────────────────────────────────────────────

    private String loadDeltaLink() {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT delta_link FROM [dbo].[sharepoint_delta_state] WHERE scope = ?",
                    String.class, SCOPE);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // Most likely V88 not applied here. Returning null means "baseline", and the save
            // below will fail the same way — so the feature is simply inert, not broken.
            log.debug("Lecture de sharepoint_delta_state impossible: {}", e.getMessage());
            return null;
        }
    }

    private void saveDeltaLink(String link, Instant syncedAt, int itemsSeen, String error) {
        try {
            int updated = jdbc.update(
                    "UPDATE [dbo].[sharepoint_delta_state] SET delta_link = ?, " +
                    "last_sync_at = COALESCE(?, last_sync_at), items_seen = items_seen + ?, " +
                    "last_error = ? WHERE scope = ?",
                    link, syncedAt == null ? null : java.sql.Timestamp.from(syncedAt),
                    itemsSeen, error, SCOPE);
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO [dbo].[sharepoint_delta_state] " +
                        "(scope, delta_link, last_sync_at, items_seen, last_error) " +
                        "VALUES (?, ?, ?, ?, ?)",
                        SCOPE, link,
                        syncedAt == null ? null : java.sql.Timestamp.from(syncedAt),
                        itemsSeen, error);
            }
        } catch (Exception e) {
            log.debug("Ecriture de sharepoint_delta_state impossible: {}", e.getMessage());
        }
    }
}
