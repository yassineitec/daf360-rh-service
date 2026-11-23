package com.daf360.rh.notification;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.common.UserScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * In-app notifications of the interview step (V132): to the panel when they are assigned, when
 * the slot moves or is cancelled, one hour before; to the RH validators when a PV comes in;
 * to the recruiter when finance answers the salary pre-validation.
 *
 * <p>Written straight through {@link InAppNotifier} rather than the routing rules: every
 * recipient here is a named person (the panel, the submitter) or a fixed permission, so a rule
 * an administrator could switch off would only be a way to lose the reminder.
 *
 * <p>Each notification carries a raw link, which the shell follows WITHOUT checking the
 * reader's permissions — what an interviewer with no RH code needs, since the CANDIDATE kind
 * alone would be filtered out for them as unreachable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewNotifier {

    /**
     * The slot is stored as the wall clock that was typed, stamped +00:00 (see the scheduling
     * form) — so it is printed in UTC to read back exactly what RH typed.
     */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    private static final String VALIDATORS_SQL =
        "SELECT DISTINCT u.id FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON rp.role_id = u.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u");

    private final InAppNotifier notifier;
    private final JdbcTemplate  jdbc;

    /** Where an interviewer lands: the candidate, on the Entretiens tab. */
    public static String interviewsLink(Long candidateId) {
        return "/rh/candidates/" + candidateId + "?tab=entretiens";
    }

    public static String formatSlot(OffsetDateTime scheduledAt) {
        return scheduledAt == null ? "" : scheduledAt.withOffsetSameInstant(ZoneOffset.UTC).format(WHEN);
    }

    public void assigned(Collection<Long> userIds, Long candidateId, String candidateName,
                         String typeName, OffsetDateTime at, String location) {
        if (userIds == null || userIds.isEmpty()) return;
        String msg = label(typeName) + " avec " + candidateName + " le " + formatSlot(at)
                + (location != null && !location.isBlank() ? " — " + location : "")
                + ". Après l'entretien, rédigez votre PV depuis la fiche du candidat.";
        afterCommit(() -> notifier.notifyUsers(userIds, InAppNotifier.MODULE_RH,
                "Entretien assigné", msg, target(candidateId)));
    }

    public void rescheduled(Collection<Long> userIds, Long candidateId, String candidateName,
                            String typeName, OffsetDateTime at) {
        if (userIds == null || userIds.isEmpty()) return;
        String msg = label(typeName) + " avec " + candidateName + " est déplacé au " + formatSlot(at) + ".";
        afterCommit(() -> notifier.notifyUsers(userIds, InAppNotifier.MODULE_RH,
                "Entretien reprogrammé", msg, target(candidateId)));
    }

    public void cancelled(Collection<Long> userIds, Long candidateId, String candidateName,
                          String typeName, OffsetDateTime at) {
        if (userIds == null || userIds.isEmpty()) return;
        String msg = label(typeName) + " avec " + candidateName + " prévu le " + formatSlot(at) + " est annulé.";
        // No link: a cancelled interview no longer opens the candidate for a non-RH reader.
        afterCommit(() -> notifier.notifyUsers(userIds, InAppNotifier.MODULE_RH,
                "Entretien annulé", msg, NotificationTarget.none()));
    }

    /** Taken off a panel that still goes ahead — no link, the record is no longer theirs. */
    public void unassigned(Collection<Long> userIds, String candidateName, String typeName, OffsetDateTime at) {
        if (userIds == null || userIds.isEmpty()) return;
        String what = typeName != null && !typeName.isBlank() ? "de l'entretien « " + typeName + " »" : "de l'entretien";
        String msg = "Vous avez été retiré du panel " + what + " avec " + candidateName
                + " le " + formatSlot(at) + ".";
        afterCommit(() -> notifier.notifyUsers(userIds, InAppNotifier.MODULE_RH,
                "Entretien retiré", msg, NotificationTarget.none()));
    }

    /** Sent by the reminder job, outside any request — no transaction to wait for. */
    public int reminder(Collection<Long> userIds, Long candidateId, String candidateName,
                        String typeName, OffsetDateTime at, String location) {
        String msg = label(typeName) + " avec " + candidateName + " commence dans moins d'une heure ("
                + formatSlot(at) + (location != null && !location.isBlank() ? ", " + location : "") + ").";
        return notifier.notifyUsers(userIds, InAppNotifier.MODULE_RH,
                "Rappel : entretien dans 1 heure", msg, target(candidateId));
    }

    /** A PV came in: whoever validates interviews in the candidate's entity. */
    public void reportSubmitted(Long paysId, Long candidateId, String candidateName,
                                String typeName, String authorName) {
        List<Long> validators = jdbc.queryForList(VALIDATORS_SQL, Long.class,
                PermissionCatalog.RH_VALIDATE_INTERVIEW, paysId);
        if (validators.isEmpty()) return;
        String msg = (authorName != null ? authorName : "Un intervieweur") + " a rédigé son PV — "
                + label(typeName) + " avec " + candidateName + ". Lisez-le et validez l'entretien.";
        afterCommit(() -> notifier.notifyUsers(validators, InAppNotifier.MODULE_RH,
                "PV d'entretien à valider", msg, target(candidateId)));
    }

    private static NotificationTarget target(Long candidateId) {
        return NotificationTarget.of(NotificationEntityType.CANDIDATE, candidateId, interviewsLink(candidateId));
    }

    private static String label(String typeName) {
        return typeName != null && !typeName.isBlank() ? "L'entretien « " + typeName + " »" : "L'entretien";
    }

    /**
     * After the business change commits — a rolled-back scheduling must not have told anyone.
     * Immediately when there is no transaction. Never throws: InAppNotifier swallows per row,
     * and this guards the rest.
     */
    private static void afterCommit(Runnable send) {
        Runnable safe = () -> {
            try { send.run(); }
            catch (Exception e) { log.warn("Interview notification failed: {}", e.getMessage()); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safe.run(); }
            });
        } else {
            safe.run();
        }
    }
}
