package com.daf360.rh.scheduler;

import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.RequestTypeCatalog;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.repository.EmployeeRequestRepository;
import com.daf360.rh.repository.RequestTypeCatalogRepository;
import com.daf360.rh.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hourly SLA escalation checks on open requests.
 *
 * Rule 1 — SLA × 1.5:
 *   Any SUBMITTED / IN_REVIEW / PENDING_L2 request where
 *   now > submission_date + (default_sla_days × 1.5 days)
 *   → notify HR Manager.
 *
 * Rule 2 — Bank-details frequency:
 *   Any employee who has submitted > 2 BANK_DETAILS requests in the last 30 days
 *   → notify HR Manager + Finance Officer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationScheduler {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final EmployeeRequestRepository    requestRepo;
    private final RequestTypeCatalogRepository typeRepo;
    private final NotificationService          notificationService;

    @Scheduled(cron = "0 0 * * * *")   // top of every hour
    @Transactional(readOnly = true)
    public void runEscalations() {
        log.info("SLA escalation check starting…");
        checkSlaBreaches();
        checkBankDetailsFrequency();
        log.info("SLA escalation check complete.");
    }

    // ── Rule 1: SLA × 1.5 ────────────────────────────────────────────────────

    void checkSlaBreaches() {
        List<RequestStatus> open = List.of(
                RequestStatus.SUBMITTED, RequestStatus.IN_REVIEW, RequestStatus.PENDING_L2);

        // Threshold = now - (max SLA × 1.5 days) as a conservative starting point;
        // per-request SLA is checked individually below.
        OffsetDateTime conservativeThreshold = OffsetDateTime.now(PARIS).minusDays(1);

        List<EmployeeRequest> candidates = requestRepo
                .findOverduePastThreshold(open, conservativeThreshold);

        // Build a type map for batch lookup (avoids N+1)
        Map<Long, RequestTypeCatalog> typeMap = typeRepo.findAllById(
                        candidates.stream().map(EmployeeRequest::getRequestTypeId)
                                .distinct().toList())
                .stream().collect(Collectors.toMap(RequestTypeCatalog::getId, Function.identity()));

        for (EmployeeRequest r : candidates) {
            RequestTypeCatalog type = typeMap.get(r.getRequestTypeId());
            if (type == null) continue;

            double slaDays  = type.getDefaultSlaDays();
            double elapsed  = (double) (OffsetDateTime.now(PARIS).toEpochSecond()
                                        - r.getSubmissionDate().toEpochSecond()) / 86_400;

            if (elapsed > slaDays * 1.5) {
                String msg = "ESCALADE SLA — Demande #" + r.getId()
                        + " (" + type.getDisplayNameFr() + ")"
                        + "\nStatut: " + r.getStatus()
                        + " | Soumis il y a " + String.format("%.1f", elapsed) + " jour(s)"
                        + " | SLA: " + slaDays + " j × 1.5 = " + (slaDays * 1.5) + " j";
                log.warn(msg);
                notificationService.sendToHrManager("⚠ SLA dépassé — demande #" + r.getId(), msg);
            }
        }
    }

    // ── Rule 2: Bank-details frequency (> 2 in 30 days) ─────────────────────

    void checkBankDetailsFrequency() {
        OffsetDateTime since = OffsetDateTime.now(PARIS).minusDays(30);

        // Find all open/recent bank-details requests to get distinct profile IDs
        List<EmployeeRequest> recent = requestRepo.findOverduePastThreshold(
                List.of(RequestStatus.SUBMITTED, RequestStatus.IN_REVIEW,
                        RequestStatus.PENDING_L2, RequestStatus.APPROVED),
                since);

        recent.stream()
                .map(EmployeeRequest::getEmployeeProfileId)
                .distinct()
                .forEach(profileId -> {
                    long count = requestRepo.countBankDetailRequestsSince(profileId, since);
                    if (count > 2) {
                        String msg = "ALERTE — profileId=" + profileId
                                + " a soumis " + count + " demandes BANK_DETAILS ces 30 derniers jours.";
                        log.warn(msg);
                        notificationService.sendToHrManager("⚠ Fréquence BANK_DETAILS élevée", msg);
                        notificationService.sendToFinance("⚠ Contrôle BANK_DETAILS requis", msg);
                    }
                });
    }
}
