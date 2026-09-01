package com.daf360.rh.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * The single writer of [dbo].[notifications].
 *
 * This INSERT used to be copy-pasted into eight classes, each with its own module string
 * (four wrote 'HR', four wrote 'RH' — the same module under two spellings, in one table).
 * Adding the deep-link columns would have meant eight identical edits and a ninth drift, so
 * every producer now goes through here instead.
 *
 * Delivery is best-effort by design: a failed notification must never fail the business
 * operation that triggered it, so each row is inserted in its own try/catch and the caller
 * is told how many landed rather than being handed an exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppNotifier {

    private static final String INSERT_SQL =
        "INSERT INTO [dbo].[notifications] " +
        "(user_id, module, title, message, is_read, created_at, entity_type, entity_id, link) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET(), ?, ?, ?)";

    /** The RH module's canonical module code. */
    public static final String MODULE_RH = "RH";

    private final JdbcTemplate jdbc;

    /**
     * Inserts one notification. Returns false if it could not be written.
     *
     * @param target never null — use {@link NotificationTarget#none()} for a notification
     *               that points nowhere.
     */
    public boolean notifyUser(Long userId, String module, String title, String message,
                              NotificationTarget target) {
        if (userId == null) {
            log.warn("In-app notification skipped — null userId (title={})", title);
            return false;
        }
        NotificationTarget t = target != null ? target : NotificationTarget.none();
        try {
            jdbc.update(INSERT_SQL,
                userId, normalizeModule(module), title, message,
                t.entityTypeName(), t.entityId(), t.link());
            return true;
        } catch (Exception ex) {
            log.error("In-app notification failed for userId={} title={}: {}",
                userId, title, ex.getMessage());
            return false;
        }
    }

    /**
     * Inserts the same notification for several users, skipping duplicate ids.
     *
     * De-duplication is not a caller convenience — recipients are resolved by permission or
     * by role, and a user holding two matching permissions used to receive the same alert
     * twice. Returns the number of rows actually written.
     */
    public int notifyUsers(Collection<Long> userIds, String module, String title, String message,
                           NotificationTarget target) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int written = 0;
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (notifyUser(userId, module, title, message, target)) written++;
        }
        return written;
    }

    /**
     * Folds the legacy 'HR' spelling into 'RH'.
     *
     * The routing engine writes `notification_event_types.module`, seeded as 'HR', while
     * every hand-written producer wrote 'RH'. The frontend keys its module chip and filters
     * off this column, so the two spellings would show up as two modules. V90 backfills the
     * existing rows; this keeps new ones consistent even if a seed row is missed.
     *
     * Any other value passes through untouched — POINTAGE, FINANCE and PAYROLL are expected
     * here once those services start writing, and must not be silently rewritten.
     */
    static String normalizeModule(String module) {
        if (module == null || module.isBlank()) return MODULE_RH;
        String trimmed = module.trim();
        return "HR".equalsIgnoreCase(trimmed) ? MODULE_RH : trimmed.toUpperCase();
    }
}
