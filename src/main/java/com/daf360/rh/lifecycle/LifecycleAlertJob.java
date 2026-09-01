package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeLifecycleAlert;
import com.daf360.rh.repository.ContractTypeConfigRepository;
import com.daf360.rh.repository.EmployeeContractRepository;
import com.daf360.rh.repository.EmployeeLifecycleAlertRepository;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.notification.NotificationEntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * D3-102: Daily CRON at 08:00 — sends lifecycle alerts for expiring contracts.
 * D3-103: Simultaneous notification to RH + IT + Directeur pays.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleAlertJob {


    private final EmployeeContractRepository       contractRepo;
    private final EmployeeLifecycleAlertRepository alertRepo;
    private final ContractTypeConfigRepository     configRepo;
    private final EmployeeLifecycleService         lifecycleService;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;

    @Scheduled(cron = "0 0 8 * * ?")
    @Transactional
    public void processLifecycleAlerts() {
        log.info("=== LIFECYCLE ALERTS JOB STARTED ===");
        LocalDate today = LocalDate.now();

        List<EmployeeLifecycleAlert> pending = alertRepo.findPendingAlerts(today);
        int sent = 0;

        for (EmployeeLifecycleAlert alert : pending) {
            try {
                sendAlert(alert);
                alert.setIsSent(true);
                alert.setSentAt(OffsetDateTime.now());
                alertRepo.save(alert);
                sent++;
            } catch (Exception e) {
                log.error("Failed to send alert id={}: {}", alert.getId(), e.getMessage());
            }
        }

        planNewAlerts(today);

        log.info("=== LIFECYCLE ALERTS JOB COMPLETE: {}/{} sent ===", sent, pending.size());
    }

    void sendAlert(EmployeeLifecycleAlert alert) {
        EmployeeContract contract = alert.getContract();
        String targetDateFr = alert.getTargetDate()
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String employeeName = lifecycleService.loadEmployeeName(alert.getEmployeeProfileId());

        // Recipients, wording and channels now come from the CONTRACT_EXPIRY routing rule.
        //
        // What this replaces: a per-alert `recipients` JSON of role names, each mapped to a
        // permission by a hardcoded switch, then queried and de-duplicated here. The three
        // permissions that switch produced are seeded as the rule's recipients (V92), so the
        // audience is unchanged — but it is now editable without a deploy.
        //
        // Deliberate trade: recipients no longer vary per contract type. They vary per rule.
        notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
            .eventCode("CONTRACT_EXPIRY")
            .paysId(contract.getPaysId())
            .entityType(NotificationEntityType.EMPLOYEE_PROFILE)
            .entityId(alert.getEmployeeProfileId())
            .templateVars(Map.of(
                "employeeName", employeeName != null ? employeeName : "",
                "contractType", contract.getContractTypeCode() != null ? contract.getContractTypeCode() : "",
                "targetDate",   targetDateFr,
                "alertType",    alert.getAlertType() != null ? alert.getAlertType() : ""))
            .build());
    }

    void planNewAlerts(LocalDate today) {
        LocalDate alertWindow = today.plusDays(30);
        List<EmployeeContract> expiring = contractRepo.findExpiringContracts(today, alertWindow);

        for (EmployeeContract c : expiring) {
            configRepo.findByPaysIdAndContractTypeCode(c.getPaysId(), c.getContractTypeCode())
                .ifPresent(config -> lifecycleService.planContractAlerts(c, config));
        }
    }
}
