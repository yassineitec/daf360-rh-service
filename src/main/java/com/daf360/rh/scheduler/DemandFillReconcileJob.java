package com.daf360.rh.scheduler;

import com.daf360.rh.service.DemandFillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety net for the « postes pourvus » rule (V137): every 15 minutes, and two minutes after
 * start-up, every demand that can change state — APPROVED, or closed AUTO_FILLED — is
 * recounted through {@link DemandFillService#recompute}.
 *
 * <p>It is also what archives, day after day, the candidatures left on a filled demand once
 * their 30 days are up (creation date + 30 — {@link DemandFillService#archiveDueAt}): a demand
 * closed AUTO_FILLED is recounted every run, and the recount archives the overdue ones.
 *
 * <p>The services call recompute themselves on every status change they make; this job exists
 * for the paths that do not go through them (a status fixed by hand in SQL, a demand that was
 * already full when V137 was applied, a notification lost to a crash between commit and
 * dispatch). Each demand runs in its own transaction, so one failure never stops the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandFillReconcileJob {

    private static final String CANDIDATES_SQL = """
        SELECT id FROM [dbo].[recruitment_demands]
         WHERE statut = 'APPROUVEE'
            OR (statut = 'CLOTUREE' AND closed_reason = 'AUTO_FILLED')
        """;

    private final JdbcTemplate      jdbc;
    private final DemandFillService fillService;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT15M")
    public void reconcile() {
        List<Long> ids;
        try {
            ids = jdbc.queryForList(CANDIDATES_SQL, Long.class);
        } catch (Exception ex) {
            // Typically V137 not applied yet: the closed_reason column is missing.
            log.warn("Demand fill reconcile skipped: {}", ex.getMessage());
            return;
        }
        int failed = 0;
        for (Long id : ids) {
            try {
                fillService.recompute(id, null);
            } catch (Exception ex) {
                failed++;
                log.warn("Demand fill reconcile failed for demand {}: {}", id, ex.getMessage());
            }
        }
        if (failed > 0) log.warn("Demand fill reconcile: {} of {} demand(s) failed", failed, ids.size());
        else log.debug("Demand fill reconcile: {} demand(s) checked", ids.size());
    }
}
