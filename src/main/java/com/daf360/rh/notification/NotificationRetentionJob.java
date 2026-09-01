package com.daf360.rh.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes old, already-read notifications.
 *
 * Nothing else ever removes a row from [dbo].[notifications], so the table only grew. Two
 * consequences, neither obvious until it hurts:
 *  - the per-user list and unread count are indexed, but the table backing them is unbounded;
 *  - FK_Notif_User has ON DELETE NO ACTION, so a user's notification history silently blocks
 *    ever deleting that user — an offboarding problem disguised as a database error.
 *
 * Deliberately conservative: only rows that are BOTH read AND older than the retention window
 * are eligible. An unread notification is never deleted regardless of age — it is someone's
 * outstanding task, and a purge that clears the inbox of anyone who was on leave for six
 * months would be a worse bug than the growth it fixes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetentionJob {

    private static final String PURGE_SQL =
        "DELETE FROM [dbo].[notifications] " +
        "WHERE is_read = 1 AND created_at < DATEADD(DAY, -?, SYSDATETIMEOFFSET())";

    private static final String COUNT_SQL =
        "SELECT COUNT(*) FROM [dbo].[notifications] " +
        "WHERE is_read = 1 AND created_at < DATEADD(DAY, -?, SYSDATETIMEOFFSET())";

    private final JdbcTemplate jdbc;

    /** Days a read notification is kept. Default 180 (~6 months). */
    @Value("${notifications.retention-days:180}")
    private int retentionDays;

    /** Set false to disable the purge entirely (e.g. while auditing a data question). */
    @Value("${notifications.retention-enabled:true}")
    private boolean enabled;

    /**
     * Runs nightly at 03:15, off the hour so it does not pile onto the other schedulers'
     * top-of-hour slots.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeOldReadNotifications() {
        if (!enabled) {
            log.debug("Notification retention purge disabled — skipping");
            return;
        }
        if (retentionDays <= 0) {
            log.warn("notifications.retention-days={} is not positive — purge skipped to avoid "
                + "deleting recent rows", retentionDays);
            return;
        }

        try {
            Integer eligible = jdbc.queryForObject(COUNT_SQL, Integer.class, retentionDays);
            if (eligible == null || eligible == 0) {
                log.debug("Notification retention: nothing older than {} days to purge", retentionDays);
                return;
            }

            int deleted = jdbc.update(PURGE_SQL, retentionDays);
            log.info("Notification retention: deleted {} read notification(s) older than {} days",
                deleted, retentionDays);
        } catch (Exception ex) {
            // Never propagate: housekeeping must not take a scheduler thread down.
            log.error("Notification retention purge failed: {}", ex.getMessage(), ex);
        }
    }
}
