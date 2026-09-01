package com.daf360.rh.lifecycle;

import com.daf360.rh.common.OffboardingStagePermissions;
import com.daf360.rh.domain.ExitInterview;
import com.daf360.rh.domain.OffboardingTask;
import com.daf360.rh.domain.OffboardingWorkflowInstance;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.repository.ExitInterviewRepository;
import com.daf360.rh.repository.OffboardingTaskRepository;
import com.daf360.rh.repository.OffboardingWorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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


    private final OffboardingWorkflowInstanceRepository instanceRepo;
    private final OffboardingTaskRepository             taskRepo;
    private final ExitInterviewRepository               interviewRepo;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;

    @Scheduled(cron = "0 5 8 * * ?")
    @Transactional
    public void processOffboardingAlerts() {
        log.info("=== OFFBOARDING ALERTS JOB STARTED ===");
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        markOverdueTasks(today, now);
        clearResolvedSlaFlags(now);
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

                    // Notify the department that OWNS the task, not everyone in RH. A
                    // laptop nobody returned is the IT officer's problem before it is the
                    // DRH's, and blanket alerts to RH are what made these ignorable.
                    String title = "SLA dépassé — offboarding";
                    String msg   = "Une tâche d'offboarding est en retard (workflow id="
                        + instanceId + ", tâche=" + task.getTaskCode() + ").";
                    dispatchTaskAlert("OFFBOARDING_TASK_OVERDUE", instance.getPaysId(), instanceId,
                        task.getTaskCode(), Map.of(
                            "instanceId", String.valueOf(instanceId),
                            "taskCode",   task.getTaskCode() != null ? task.getTaskCode() : "",
                            "taskLabel",  task.getTaskLabel() != null ? task.getTaskLabel() : ""));
                }
                log.info("Marked overdue task id={} workflowId={}", task.getId(), instanceId);
            } catch (Exception e) {
                log.error("Failed to mark overdue task id={}: {}", task.getId(), e.getMessage());
            }
        }
    }

    // ── 1b. Clear the SLA flag once nothing is overdue ────────────────────────

    /**
     * `sla_breach_flag` was set and never unset, so the red "SLA dépassé" badge stayed on a
     * file forever — including after every late task had been dealt with. A permanent alarm
     * is the same as no alarm.
     *
     * Also un-blocks the instance: `markOverdueTasks` sets BLOCKED for an SLA breach, and the
     * only other place that clears it (`completeTask`) tests for outstanding *blocking* tasks,
     * which is a different question.
     */
    private void clearResolvedSlaFlags(OffsetDateTime now) {
        for (OffboardingWorkflowInstance instance : instanceRepo.findBySlaBreachFlagTrue()) {
            try {
                boolean stillLate = taskRepo.findByWorkflowInstanceId(instance.getId()).stream()
                    .anyMatch(t -> !"DONE".equals(t.getStatus())
                                && !"SKIPPED".equals(t.getStatus())
                                && t.getSlaBreachDate() != null);
                if (stillLate) continue;

                instance.setSlaBreachFlag(false);
                if ("BLOCKED".equals(instance.getStatus())
                        && taskRepo.findBlockingIncomplete(instance.getId()).isEmpty()) {
                    instance.setStatus("IN_PROGRESS");
                }
                instance.setUpdatedAt(now);
                instanceRepo.save(instance);
                log.info("Cleared SLA breach flag on workflow id={}", instance.getId());
            } catch (Exception e) {
                log.error("Failed to clear SLA flag on workflow id={}: {}",
                    instance.getId(), e.getMessage());
            }
        }
    }

    // ── 2. Send reminders for tasks due tomorrow ──────────────────────────────

    private void sendTomorrowReminders(LocalDate today) {
        LocalDate tomorrow = today.plusDays(1);
        List<OffboardingTask> dueTomorrow =
            taskRepo.findDueOnForActiveInstances(List.of("PENDING", "IN_PROGRESS"), tomorrow);

        for (OffboardingTask task : dueTomorrow) {
            try {
                Long instanceId = task.getWorkflowInstance().getId();
                Long paysId     = task.getWorkflowInstance().getPaysId();
                String title = "Rappel — tâche offboarding échéance demain";
                String msg   = "La tâche '" + task.getTaskLabel()
                    + "' (workflow id=" + instanceId + ") arrive à échéance demain.";
                // To the owning department — see markOverdueTasks.
                dispatchTaskAlert("OFFBOARDING_TASK_DUE_SOON", paysId, instanceId,
                    task.getTaskCode(), Map.of(
                        "instanceId", String.valueOf(instanceId),
                        "taskCode",   task.getTaskCode() != null ? task.getTaskCode() : "",
                        "taskLabel",  task.getTaskLabel() != null ? task.getTaskLabel() : ""));
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
                    notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
                        .eventCode("OFFBOARDING_BLOCKED")
                        .paysId(instance.getPaysId())
                        .entityType(NotificationEntityType.OFFBOARDING)
                        .entityId(instance.getId())
                        .templateVars(Map.of("instanceId", String.valueOf(instance.getId())))
                        .build());
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
    /**
     * Raises one of the two per-task offboarding alerts.
     *
     * The permission goes in the CONTEXT, not the rule: which department owns the alert
     * depends on the task — IT for a laptop, payroll for a final settlement — and a rule's
     * recipient list is static. `dynamicPermission` keeps that per-stage precision while the
     * title, body, channels and any extra recipients become admin-editable.
     */
    private void dispatchTaskAlert(String eventCode, Long paysId, Long instanceId,
                                   String taskCode, Map<String, String> vars) {
        notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
            .eventCode(eventCode)
            .paysId(paysId)
            .entityType(NotificationEntityType.OFFBOARDING)
            .entityId(instanceId)
            .dynamicPermission(OffboardingStagePermissions.forTaskCode(taskCode))
            .templateVars(vars)
            .build());
    }

}
