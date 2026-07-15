package com.daf360.rh.lifecycle;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.ExitInterview;
import com.daf360.rh.domain.OffboardingTask;
import com.daf360.rh.domain.OffboardingWorkflowInstance;
import com.daf360.rh.repository.ExitInterviewRepository;
import com.daf360.rh.repository.OffboardingTaskRepository;
import com.daf360.rh.repository.OffboardingWorkflowInstanceRepository;
import com.daf360.rh.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Daily CRON at 08:05 — offboarding SLA alerts and exit interview anonymisation.
 * Runs 5 minutes after LifecycleAlertJob (which runs at 08:00).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OffboardingAlertJob {

    private static final String INSERT_NOTIF_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET())";

    private static final String USERS_WITH_PERM_SQL =
        "SELECT u.id, COALESCE(u.username, u.email) as email " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON u.role_id = rp.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL)";

    private final OffboardingWorkflowInstanceRepository instanceRepo;
    private final OffboardingTaskRepository             taskRepo;
    private final ExitInterviewRepository               interviewRepo;
    private final MailService                           mailService;
    private final JdbcTemplate                         jdbc;

    @Scheduled(cron = "0 5 8 * * ?")
    @Transactional
    public void processOffboardingAlerts() {
        log.info("=== OFFBOARDING ALERTS JOB STARTED ===");
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        markOverdueTasks(today, now);
        sendTomorrowReminders(today);
        escalateStaleBlockedInstances(now);
        anonymiseEligibleInterviews(now);

        log.info("=== OFFBOARDING ALERTS JOB COMPLETE ===");
    }

    // ── 1. Mark overdue tasks → BLOCKED, set sla_breach_date ─────────────────

    private void markOverdueTasks(LocalDate today, OffsetDateTime now) {
        List<OffboardingTask> overdue = taskRepo.findOverdueTasks(today);
        Set<Long> instancesUpdated = new HashSet<>();

        for (OffboardingTask task : overdue) {
            try {
                task.setStatus("BLOCKED");
                task.setSlaBreachDate(now);
                taskRepo.save(task);

                Long instanceId = task.getWorkflowInstance().getId();
                if (instancesUpdated.add(instanceId)) {
                    OffboardingWorkflowInstance instance =
                        task.getWorkflowInstance();
                    instance.setSlaBreachFlag(true);
                    instance.setStatus("BLOCKED");
                    instance.setUpdatedAt(now);
                    instanceRepo.save(instance);

                    // Notify RH
                    String title = "SLA dépassé — offboarding";
                    String msg   = "Une tâche d'offboarding est en retard (workflow id="
                        + instanceId + ", tâche=" + task.getTaskCode() + ").";
                    notifyByPermission(instance.getPaysId(),
                        PermissionCatalog.RH_MANAGE_OFFBOARDING, title, msg);
                }
                log.info("Marked overdue task id={} workflowId={}", task.getId(), instanceId);
            } catch (Exception e) {
                log.error("Failed to mark overdue task id={}: {}", task.getId(), e.getMessage());
            }
        }
    }

    // ── 2. Send reminders for tasks due tomorrow ──────────────────────────────

    private void sendTomorrowReminders(LocalDate today) {
        LocalDate tomorrow = today.plusDays(1);
        List<OffboardingTask> dueTomorrow =
            taskRepo.findByStatusInAndDueDate(List.of("PENDING", "IN_PROGRESS"), tomorrow);

        for (OffboardingTask task : dueTomorrow) {
            try {
                Long instanceId = task.getWorkflowInstance().getId();
                Long paysId     = task.getWorkflowInstance().getPaysId();
                String title = "Rappel — tâche offboarding échéance demain";
                String msg   = "La tâche '" + task.getTaskLabel()
                    + "' (workflow id=" + instanceId + ") arrive à échéance demain.";
                notifyByPermission(paysId,
                    PermissionCatalog.RH_MANAGE_OFFBOARDING, title, msg);
            } catch (Exception e) {
                log.error("Failed to send reminder for task id={}: {}", task.getId(), e.getMessage());
            }
        }
    }

    // ── 3. Escalate BLOCKED instances idle >3 days ───────────────────────────

    private void escalateStaleBlockedInstances(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusDays(3);
        List<OffboardingWorkflowInstance> blocked = instanceRepo.findByStatus("BLOCKED");

        for (OffboardingWorkflowInstance instance : blocked) {
            try {
                OffsetDateTime lastUpdate = instance.getUpdatedAt() != null
                    ? instance.getUpdatedAt() : instance.getCreatedAt();
                if (lastUpdate != null && lastUpdate.isBefore(cutoff)) {
                    String title = "Escalade — offboarding bloqué";
                    String msg   = "Le workflow d'offboarding id=" + instance.getId()
                        + " est bloqué depuis plus de 3 jours. Intervention requise.";
                    notifyByPermission(instance.getPaysId(),
                        PermissionCatalog.RH_VALIDATE_OFFBOARDING, title, msg);
                    log.info("Escalated stale blocked workflow id={}", instance.getId());
                }
            } catch (Exception e) {
                log.error("Failed to escalate workflow id={}: {}", instance.getId(), e.getMessage());
            }
        }
    }

    // ── 4. Anonymise exit interviews validated >12 months ago ────────────────

    private void anonymiseEligibleInterviews(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minusMonths(12);
        List<ExitInterview> eligible = interviewRepo.findEligibleForAnonymisation(cutoff);

        for (ExitInterview interview : eligible) {
            try {
                interview.setFeedbackText(null);
                interview.setDepartureReasons(null);
                interview.setIsAnonymised(true);
                interview.setAnonymisedAt(now);
                interview.setUpdatedAt(now);
                interviewRepo.save(interview);
                log.info("Anonymised exit interview id={}", interview.getId());
            } catch (Exception e) {
                log.error("Failed to anonymise interview id={}: {}", interview.getId(), e.getMessage());
            }
        }
    }

    // ── Notification helper ───────────────────────────────────────────────────

    private void notifyByPermission(Long paysId, String permission, String title, String message) {
        try {
            List<Map<String, Object>> users =
                jdbc.queryForList(USERS_WITH_PERM_SQL, permission, paysId);
            List<String> emails = new ArrayList<>();

            for (Map<String, Object> row : users) {
                Long uid   = ((Number) row.get("id")).longValue();
                String email = (String) row.get("email");
                try {
                    jdbc.update(INSERT_NOTIF_SQL, uid, "RH", title, message);
                } catch (Exception ex) {
                    log.error("In-app notification failed for userId={}: {}", uid, ex.getMessage());
                }
                if (email != null && !email.isBlank()) {
                    emails.add(email);
                }
            }
            if (!emails.isEmpty()) {
                try {
                    mailService.sendRoutedEmail(emails, List.of(), List.of(),
                        "[DAF360 RH] " + title, message);
                } catch (Exception ex) {
                    log.warn("Email notification failed: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("notifyByPermission failed for permission={}: {}", permission, e.getMessage());
        }
    }
}
