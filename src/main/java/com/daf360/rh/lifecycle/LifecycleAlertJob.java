package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeLifecycleAlert;
import com.daf360.rh.repository.ContractTypeConfigRepository;
import com.daf360.rh.repository.EmployeeContractRepository;
import com.daf360.rh.repository.EmployeeLifecycleAlertRepository;
import com.daf360.rh.service.MailService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final String INSERT_NOTIF_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET())";

    private static final String USERS_WITH_PERM_SQL =
        "SELECT u.id, COALESCE(u.username, u.email) as email " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON u.role_id = rp.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL)";

    private final EmployeeContractRepository       contractRepo;
    private final EmployeeLifecycleAlertRepository alertRepo;
    private final ContractTypeConfigRepository     configRepo;
    private final EmployeeLifecycleService         lifecycleService;
    private final MailService                      mailService;
    private final JdbcTemplate                     jdbc;
    private final ObjectMapper                     objectMapper;

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
        String contractType = contract.getContractTypeCode();
        String targetDateFr = alert.getTargetDate()
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

        String employeeName = lifecycleService.loadEmployeeName(alert.getEmployeeProfileId());

        String title = "Échéance de contrat — " + employeeName;
        String body  = "Le contrat " + contractType + " de " + employeeName
            + " arrive à échéance le " + targetDateFr
            + ".\nAction requise : renouvellement, conversion ou clôture du dossier.";

        List<String> recipientRoles = parseRecipients(alert.getRecipients());
        Set<Long> notified = new HashSet<>();

        for (String role : recipientRoles) {
            String permission = roleToPermission(role);
            if (permission == null) continue;

            List<Map<String, Object>> users = jdbc.queryForList(
                USERS_WITH_PERM_SQL, permission, contract.getPaysId());

            for (Map<String, Object> row : users) {
                Long uid   = ((Number) row.get("id")).longValue();
                String email = (String) row.get("email");
                if (!notified.add(uid)) continue;

                try {
                    jdbc.update(INSERT_NOTIF_SQL, uid, "RH", title, body);
                } catch (Exception ex) {
                    log.error("In-app alert notification failed for userId={}: {}", uid, ex.getMessage());
                }
                if (email != null && !email.isBlank()) {
                    try {
                        mailService.sendRoutedEmail(
                            List.of(email), List.of(), List.of(),
                            "[DAF360 RH] " + title, body);
                    } catch (Exception ex) {
                        log.warn("Email alert failed for userId={}: {}", uid, ex.getMessage());
                    }
                }
            }
        }
    }

    void planNewAlerts(LocalDate today) {
        LocalDate alertWindow = today.plusDays(30);
        List<EmployeeContract> expiring = contractRepo.findExpiringContracts(today, alertWindow);

        for (EmployeeContract c : expiring) {
            configRepo.findByPaysIdAndContractTypeCode(c.getPaysId(), c.getContractTypeCode())
                .ifPresent(config -> lifecycleService.planContractAlerts(c, config));
        }
    }

    private List<String> parseRecipients(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse alert recipients JSON: {}", json);
            return List.of("RH");
        }
    }

    private String roleToPermission(String role) {
        return switch (role) {
            case "RH"             -> "RH_VIEW_CONTRACTS";
            case "IT"             -> "RH_MANAGE_LIFECYCLE";
            case "DIRECTEUR_PAYS" -> "RH_APPROVE_RECRUITMENT_DEMAND";
            default               -> null;
        };
    }
}
