package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.ContractTypeConfig;
import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeLifecycleAlert;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.NotificationRoutingService.DispatchResult;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.ContractTypeConfigRepository;
import com.daf360.rh.repository.EmployeeContractRepository;
import com.daf360.rh.repository.EmployeeLifecycleAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * D3-102: daily alerts, at 08:00, before a contract ends (CONTRACT_EXPIRY) and before a trial
 * period ends (TRIAL_PERIOD_END). Lead times are per (pays, contract type), edited in
 * Administration › Échéances de contrat.
 *
 * <h3>Computed every run, not planned in advance</h3>
 * This job used to plan one row per contract when the contract was created and then only send
 * rows whose date had come. Everything the plan froze went stale: a lead time changed after
 * creation, a CDD renewed onto a new end date, a contract created already inside its window
 * (its alert date was in the past, so it was never planned), a lead time above the 30-day scan.
 * Each of those meant HR was never told.
 *
 * <p>Now each run asks, for every active contract: is today inside
 * {@code [target − lead, target]}? If so and this (contract, type, target date) has not been
 * delivered yet, send it. The ledger row in {@code employee_lifecycle_alerts} is only the
 * memory of what was delivered — which is also why a missed day catches up on the next run,
 * and why a renewal (new target date) is announced again.
 *
 * <h3>Sent means delivered</h3>
 * A row is marked sent only when the dispatch reached at least one person. No rule, no
 * recipient, a failure: it stays pending and the next run retries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleAlertJob {

    public static final String TYPE_CONTRACT_EXPIRY = "CONTRACT_EXPIRY";
    public static final String TYPE_TRIAL_END       = "TRIAL_PERIOD_END";

    /** Rows written before this rewrite carry the old name; one already sent still counts. */
    private static final List<String> CONTRACT_EXPIRY_TYPES = List.of(TYPE_CONTRACT_EXPIRY, "CONTRACT_EXPIRY_30D");
    private static final List<String> TRIAL_END_TYPES       = List.of(TYPE_TRIAL_END);

    /** Upper bound of a configurable lead time — and so of how far ahead a run looks. */
    public static final int MAX_LEAD_DAYS = 365;
    /** Used when the (pays, type) has no configuration row: an alert beats silence. */
    static final int DEFAULT_EXPIRY_LEAD = 30;
    static final int DEFAULT_TRIAL_LEAD  = 15;

    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final EmployeeContractRepository       contractRepo;
    private final EmployeeLifecycleAlertRepository alertRepo;
    private final ContractTypeConfigRepository     configRepo;
    private final EmployeeLifecycleService         lifecycleService;
    private final NotificationRoutingService       notificationRoutingService;

    /** The cron and the manual endpoint may overlap; a second run waits for nobody, it just declines. */
    private final ReentrantLock running = new ReentrantLock();

    public record RunSummary(boolean ran, int due, int sent, int pending) {}

    // @Transactional on both entry points: the scheduler and the controller each come in
    // through the proxy, and the inner call from scheduledRun does not. One transaction per
    // run keeps lazy associations readable; the notification rows and the "sent" flags then
    // commit — or roll back — together, so a crashed run is simply retried the next day.
    @Scheduled(cron = "0 0 8 * * ?")
    @Transactional
    public void scheduledRun() {
        processLifecycleAlerts();
    }

    @Transactional
    public RunSummary processLifecycleAlerts() {
        if (!running.tryLock()) {
            log.warn("Lifecycle alert job already running — this trigger is ignored");
            return new RunSummary(false, 0, 0, 0);
        }
        try {
            return run(LocalDate.now());
        } finally {
            running.unlock();
        }
    }

    RunSummary run(LocalDate today) {
        log.info("=== LIFECYCLE ALERTS JOB STARTED ({}) ===", today);
        LocalDate horizon = today.plusDays(MAX_LEAD_DAYS);
        Map<String, Optional<ContractTypeConfig>> configs = new HashMap<>();
        int[] counts = new int[3];   // due, sent, pending

        for (EmployeeContract c : contractRepo.findExpiringContracts(today, horizon)) {
            int lead = config(configs, c)
                .map(ContractTypeConfig::getAlertDaysBeforeExpiry)
                .orElse(DEFAULT_EXPIRY_LEAD);
            handle(c, TYPE_CONTRACT_EXPIRY, CONTRACT_EXPIRY_TYPES, c.getDateFinPrevue(), lead, today, counts);
        }

        for (EmployeeContract c : contractRepo.findTrialPeriodsEnding(today, horizon)) {
            LocalDate end = effectiveTrialEnd(c);
            // A config with 0 trial days yields end == start: there is no trial to announce.
            if (end == null || end.isBefore(today)
                    || (c.getDateDebut() != null && !end.isAfter(c.getDateDebut()))) continue;
            int lead = config(configs, c)
                .map(ContractTypeConfig::getAlertDaysBeforeTrialEnd)
                .orElse(DEFAULT_TRIAL_LEAD);
            handle(c, TYPE_TRIAL_END, TRIAL_END_TYPES, end, lead, today, counts);
        }

        log.info("=== LIFECYCLE ALERTS JOB COMPLETE: due={} sent={} still pending={} ===",
            counts[0], counts[1], counts[2]);
        return new RunSummary(true, counts[0], counts[1], counts[2]);
    }

    /** The renewed date when the trial was renewed, else the initial one. */
    static LocalDate effectiveTrialEnd(EmployeeContract c) {
        if (Boolean.TRUE.equals(c.getPeriodeEssaiRenouvelee()) && c.getDateFinPeRenouvellement() != null) {
            return c.getDateFinPeRenouvellement();
        }
        return c.getDateFinPeriodeEssai();
    }

    private Optional<ContractTypeConfig> config(Map<String, Optional<ContractTypeConfig>> cache,
                                                EmployeeContract c) {
        // contract_type_code holds a CONTRACT_TYPE list id; config rows are keyed by its nature
        // (CDI, CDD…), and a pays without its own row borrows the reference one.
        String nature = lifecycleService.natureOf(c);
        return cache.computeIfAbsent(c.getPaysId() + "|" + nature, k -> {
            Optional<ContractTypeConfig> found = configRepo.findForNature(c.getPaysId(), nature);
            if (found.isEmpty()) {
                log.warn("No contract_type_config for pays={} nature={} (type id {}) — default lead times used",
                    c.getPaysId(), nature, c.getContractTypeId());
            }
            return found;
        });
    }

    private void handle(EmployeeContract c, String alertType, List<String> knownTypes,
                        LocalDate target, int lead, LocalDate today, int[] counts) {
        if (target == null || today.isBefore(target.minusDays(Math.max(lead, 0)))) return;

        Optional<EmployeeLifecycleAlert> existing =
            alertRepo.findFirstByContractIdAndAlertTypeInAndTargetDateOrderByIdAsc(c.getId(), knownTypes, target);
        if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsSent())) return;

        counts[0]++;
        EmployeeLifecycleAlert alert = existing.orElseGet(() -> EmployeeLifecycleAlert.builder()
            .contract(c)
            .employeeProfileId(c.getEmployeeProfile().getId())
            .paysId(c.getPaysId())
            .alertType(alertType)
            .alertDate(target.minusDays(Math.max(lead, 0)))
            .targetDate(target)
            // Recipients are the routing rule's business since V92; kept for the NOT NULL column.
            .recipients("[]")
            .isSent(false)
            .build());

        DispatchResult result;
        try {
            result = send(c, alertType, target, today);
        } catch (Exception e) {
            log.error("Lifecycle alert {} for contract {} failed: {}", alertType, c.getId(), e.getMessage());
            result = null;
        }

        if (result != null && result.delivered()) {
            alert.setIsSent(true);
            alert.setSentAt(OffsetDateTime.now());
            counts[1]++;
        } else {
            counts[2]++;
            log.warn("Lifecycle alert {} for contract {} (target {}) reached nobody — kept pending, "
                + "retried next run. Check the {} routing rule and its recipients for pays={}.",
                alertType, c.getId(), target, alertType, c.getPaysId());
        }
        try {
            alertRepo.save(alert);
        } catch (Exception e) {
            // Most likely the V118 unique index: another instance recorded the same occurrence.
            // (Usually that only surfaces at commit, rolling this run back — it then retries.)
            log.error("Could not record lifecycle alert {} for contract {}: {}", alertType, c.getId(), e.getMessage());
        }
    }

    private DispatchResult send(EmployeeContract c, String alertType, LocalDate target, LocalDate today) {
        Long profileId = c.getEmployeeProfile().getId();
        String employeeName = lifecycleService.loadEmployeeName(profileId);
        return notificationRoutingService.dispatchNow(RoutingContext.builder()
            .eventCode(alertType)
            .paysId(c.getPaysId())
            .entityType(NotificationEntityType.EMPLOYEE_PROFILE)
            .entityId(profileId)
            .templateVars(Map.of(
                "employeeName", employeeName != null ? employeeName : "",
                // The label people know (« CDD — Durée déterminée »…), not the stored list id.
                "contractType", lifecycleService.contractTypeLabel(c),
                "targetDate",   target.format(FR),
                "daysLeft",     String.valueOf(ChronoUnit.DAYS.between(today, target)),
                "alertType",    alertType))
            .build());
    }
}
