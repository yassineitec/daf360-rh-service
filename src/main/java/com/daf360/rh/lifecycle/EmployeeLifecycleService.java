package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.*;
import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.MailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeLifecycleService {

    private static final List<String> TERMINAL_STATUSES = List.of(
        "INACTIF", "FIN_CONTRAT", "FIN_STAGE", "FIN_MISSION",
        "RESILIATION", "RETRAITE", "RUPTURE_PE"
    );

    private static final String INSERT_NOTIF_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET())";

    private static final String USERS_WITH_PERM_SQL =
        "SELECT u.id, COALESCE(u.username, u.email) as email " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON u.role_id = rp.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL)";

    private static final String EMPLOYEE_NAME_SQL =
        "SELECT COALESCE(u.fullName, u.username, u.email, 'Collaborateur') " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id " +
        "WHERE ep.id = ?";

    private final EmployeeContractRepository         contractRepo;
    private final EmployeeLifecycleTransitionRepository transitionRepo;
    private final EmployeeLifecycleAlertRepository   alertRepo;
    private final ContractTypeConfigRepository       configRepo;
    private final EmployeeProfileRepository          profileRepo;
    private final LifecycleStateMachine              stateMachine;
    private final MailService                        mailService;
    private final JdbcTemplate                       jdbc;
    private final ObjectMapper                       objectMapper;

    // ── Create contract ───────────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_CREATE_CONTRACT')")
    public ContractDetailDto createContract(CreateContractRequest dto, Long createdBy) {
        return doCreateContract(dto, createdBy);
    }

    /** Called internally by the hire bridge — security is enforced at the calling endpoint level. */
    public ContractDetailDto createContractFromBridge(CreateContractRequest dto, Long createdBy) {
        return doCreateContract(dto, createdBy);
    }

    private ContractDetailDto doCreateContract(CreateContractRequest dto, Long createdBy) {
        EmployeeProfile profile = profileRepo.findById(dto.getEmployeeProfileId())
            .orElseThrow(() -> new BusinessRuleException("D3-95", "Collaborateur introuvable."));

        ContractTypeConfig config = configRepo
            .findByPaysIdAndContractTypeCode(dto.getPaysId(), dto.getContractTypeCode())
            .orElseThrow(() -> new BusinessRuleException("D3-95",
                "Configuration de contrat introuvable pour ce pays et type de contrat."));

        String initialStatus = switch (dto.getContractTypeCode()) {
            case "STAGE"     -> "CONVENTION_SIGNEE";
            case "FREELANCE" -> "SOURCING_PRESTATAIRE";
            default          -> "RECRUTEMENT";
        };

        if ("CIVP".equals(dto.getContractTypeCode())) {
            validateCIVPEligibility(profile, dto, config);
        }

        if ("CDD".equals(dto.getContractTypeCode()) && dto.getCddContratParentId() != null) {
            EmployeeContract parent = contractRepo.findById(dto.getCddContratParentId())
                .orElseThrow(() -> new BusinessRuleException("D3-97", "Contrat CDD parent introuvable."));
            if (parent.getCddRenouvellementCount() >= 1) {
                throw new BusinessRuleException("D3-97",
                    "Un CDD ne peut être renouvelé qu'une seule fois.");
            }
        }

        LocalDate trialEnd = calculateTrialEndDate(
            dto.getDateDebut(), dto.getContractTypeCode(), dto.isManagerProfile(), config);

        EmployeeContract contract = EmployeeContract.builder()
            .employeeProfile(profile)
            .paysId(dto.getPaysId())
            .contractTypeCode(dto.getContractTypeCode())
            .currentStatusCode(initialStatus)
            .dateDebut(dto.getDateDebut())
            .dateFinPrevue(dto.getDateFinPrevue())
            .dateFinPeriodeEssai(trialEnd)
            .referenceContrat(dto.getReferenceContrat())
            .civpAnetiReference(dto.getCivpAnetiReference())
            .civpConventionDate(dto.getCivpConventionDate())
            .stageEcole(dto.getStageEcole())
            .stageTuteurId(dto.getStageTuteurId())
            .stageConventionSignee(dto.getStageConventionSignee() != null && dto.getStageConventionSignee())
            .freelanceTjm(dto.getFreelanceTjm())
            .freelanceDevise(dto.getFreelanceDevise() != null ? dto.getFreelanceDevise() : "EUR")
            .freelanceSociete(dto.getFreelanceSociete())
            .detachementEntiteOrigineId(dto.getDetachementEntiteOrigineId())
            .detachementEntiteAccueilId(dto.getDetachementEntiteAccueilId())
            .detachementRetourPrevu(dto.getDetachementRetourPrevu())
            .isActive(true)
            .createdBy(createdBy)
            .build();

        if (dto.getCddContratParentId() != null) {
            contractRepo.findById(dto.getCddContratParentId())
                .ifPresent(contract::setCddContratParent);
        }

        contractRepo.save(contract);

        logTransition(contract, null, initialStatus, "CREATE_CONTRACT", createdBy, null, null);

        profile.setCurrentContractId(contract.getId());
        profile.setLifecycleStatusCode(initialStatus);
        profileRepo.save(profile);

        planContractAlerts(contract, config);

        return mapToDetailDto(contract);
    }

    // ── Transition state ──────────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto transitionState(Long contractId, TransitionRequest dto, Long triggeredBy) {
        EmployeeContract contract = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-96", "Contrat introuvable."));

        if (!stateMachine.isTransitionAllowed(
                contract.getContractTypeCode(),
                contract.getCurrentStatusCode(),
                dto.getNewStatus())) {
            throw new BusinessRuleException("D3-96",
                "Transition non autorisée : " + contract.getCurrentStatusCode()
                + " → " + dto.getNewStatus()
                + " pour contrat type " + contract.getContractTypeCode() + ".");
        }

        if (Boolean.TRUE.equals(contract.getDossierLocked())) {
            throw new BusinessRuleException("D3-104",
                "Le dossier de ce collaborateur est verrouillé en lecture seule.");
        }

        String previousStatus = contract.getCurrentStatusCode();

        if (dto.getEndReasonCode() != null) {
            contract.setEndReasonCode(dto.getEndReasonCode());
        }
        if (TERMINAL_STATUSES.contains(dto.getNewStatus())) {
            contract.setIsActive(false);
            contract.setDateFinReelle(LocalDate.now());
            if ("INACTIF".equals(dto.getNewStatus())) {
                contract.setDossierLocked(true);
            }
        }

        contract.setCurrentStatusCode(dto.getNewStatus());
        contract.setUpdatedAt(OffsetDateTime.now());
        contractRepo.save(contract);

        logTransition(contract, previousStatus, dto.getNewStatus(),
            dto.getActionCode(), triggeredBy, dto.getCommentaire(), dto.getDocumentReference());

        profileRepo.findById(contract.getEmployeeProfile().getId()).ifPresent(p -> {
            p.setLifecycleStatusCode(dto.getNewStatus());
            profileRepo.save(p);
        });

        triggerTransitionNotifications(contract, previousStatus, dto.getNewStatus(), triggeredBy);

        return mapToDetailDto(contract);
    }

    // ── Validate trial period ─────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_TRIAL_PERIOD')")
    public ContractDetailDto validateTrialPeriod(Long contractId, ValidateTrialRequest dto, Long validatedBy) {
        EmployeeContract contract = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-96", "Contrat introuvable."));

        if (!"PERIODE_ESSAI".equals(contract.getCurrentStatusCode())) {
            throw new BusinessRuleException("D3-96",
                "Ce contrat n'est pas en période d'essai.");
        }

        String newStatus  = Boolean.TRUE.equals(dto.getApproved()) ? "ACTIF"      : "RUPTURE_PE";
        String actionCode = Boolean.TRUE.equals(dto.getApproved()) ? "VALIDATE_PE": "RUPTURE_PE";

        return transitionState(contractId,
            TransitionRequest.builder()
                .newStatus(newStatus)
                .actionCode(actionCode)
                .commentaire(dto.getCommentaire())
                .build(),
            validatedBy);
    }

    // ── CDD: renew ────────────────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto renewCDD(Long contractId, RenewCDDRequest dto, Long renewedBy) {
        EmployeeContract contract = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-97", "Contrat introuvable."));

        if (!"CDD".equals(contract.getContractTypeCode())) {
            throw new BusinessRuleException("D3-97", "Ce contrat n'est pas un CDD.");
        }
        if (contract.getCddRenouvellementCount() >= 1) {
            throw new BusinessRuleException("D3-97",
                "Ce CDD a déjà été renouvelé une fois. Un seul renouvellement est autorisé.");
        }
        if (contract.getDateFinPrevue() != null
                && LocalDate.now().isAfter(contract.getDateFinPrevue())) {
            throw new BusinessRuleException("D3-97",
                "Le renouvellement doit être formalisé avant la date de terme initiale.");
        }

        contract.setDateFinPrevue(dto.getNewDateFin());
        contract.setCddRenouvellementCount(1);
        contract.setCurrentStatusCode("RENOUVELLEMENT_CDD");
        contract.setUpdatedAt(OffsetDateTime.now());
        contractRepo.save(contract);

        logTransition(contract, "ACTIF", "RENOUVELLEMENT_CDD",
            "RENEWAL_CDD", renewedBy, dto.getCommentaire(), null);

        profileRepo.findById(contract.getEmployeeProfile().getId()).ifPresent(p -> {
            p.setLifecycleStatusCode("RENOUVELLEMENT_CDD");
            profileRepo.save(p);
        });

        configRepo.findByPaysIdAndContractTypeCode(contract.getPaysId(), "CDD")
            .ifPresent(config -> planContractAlerts(contract, config));

        return mapToDetailDto(contract);
    }

    // ── CDD: convert to CDI ───────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto convertToCDI(Long contractId, ConvertToCDIRequest dto, Long convertedBy) {
        EmployeeContract cdd = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-97", "Contrat introuvable."));

        if (!"CDD".equals(cdd.getContractTypeCode())) {
            throw new BusinessRuleException("D3-97", "Ce contrat n'est pas un CDD.");
        }

        transitionState(contractId,
            TransitionRequest.builder()
                .newStatus("CONVERSION_CDI")
                .actionCode("CONVERT_TO_CDI")
                .commentaire("Conversion en CDI")
                .build(),
            convertedBy);

        CreateContractRequest cdiRequest = new CreateContractRequest();
        cdiRequest.setEmployeeProfileId(cdd.getEmployeeProfile().getId());
        cdiRequest.setPaysId(cdd.getPaysId());
        cdiRequest.setContractTypeCode("CDI");
        cdiRequest.setDateDebut(dto.getCdiStartDate());

        return createContract(cdiRequest, convertedBy);
    }

    // ── Alert planning ────────────────────────────────────────────────────────

    public void planContractAlerts(EmployeeContract contract, ContractTypeConfig config) {
        if (contract.getDateFinPrevue() == null) return;

        int alertDays  = config.getAlertDaysBeforeExpiry() != null ? config.getAlertDaysBeforeExpiry() : 30;
        LocalDate alertDate = contract.getDateFinPrevue().minusDays(alertDays);

        if (alertDate.isBefore(LocalDate.now())) return;

        if (alertRepo.existsByContractIdAndAlertType(contract.getId(), "CONTRACT_EXPIRY_30D")) return;

        try {
            List<String> recipientRoles = List.of("RH", "IT", "DIRECTEUR_PAYS");
            String recipientsJson = objectMapper.writeValueAsString(recipientRoles);

            EmployeeLifecycleAlert alert = EmployeeLifecycleAlert.builder()
                .contract(contract)
                .employeeProfileId(contract.getEmployeeProfile().getId())
                .alertType("CONTRACT_EXPIRY_30D")
                .alertDate(alertDate)
                .targetDate(contract.getDateFinPrevue())
                .recipients(recipientsJson)
                .isSent(false)
                .build();
            alertRepo.save(alert);
        } catch (Exception e) {
            log.error("Failed to plan contract alert for contract {}: {}", contract.getId(), e.getMessage());
        }
    }

    // ── Query methods ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'RH_VIEW_CONTRACTS')")
    public List<ContractListDto> getContractsForEmployee(Long employeeProfileId) {
        return contractRepo.findByEmployeeProfileIdOrderByCreatedAtDesc(employeeProfileId)
            .stream().map(this::mapToListDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'RH_VIEW_CONTRACTS')")
    public ContractDetailDto getContract(Long contractId) {
        return contractRepo.findById(contractId)
            .map(this::mapToDetailDto)
            .orElseThrow(() -> new BusinessRuleException("D3-96", "Contrat introuvable."));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'RH_VIEW_CONTRACTS')")
    public List<ContractTransitionHistoryDto> getContractHistory(Long contractId) {
        return transitionRepo.findByContractIdOrderByTriggeredAtAsc(contractId)
            .stream().map(this::mapToHistoryDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'RH_VIEW_CONTRACTS')")
    public List<ContractTransitionHistoryDto> getEmployeeLifecycleHistory(Long employeeProfileId) {
        return transitionRepo.findByEmployeeProfileIdOrderByTriggeredAtAsc(employeeProfileId)
            .stream().map(this::mapToHistoryDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'RH_MANAGE_ALERTS')")
    public List<LifecycleAlertDto> getAlerts(Long paysId, Boolean acknowledged) {
        List<EmployeeLifecycleAlert> alerts;
        if (acknowledged == null) {
            alerts = alertRepo.findAll();
        } else {
            alerts = alertRepo.findAll().stream()
                .filter(a -> acknowledged.equals(a.getIsAcknowledged()))
                .collect(Collectors.toList());
        }
        if (paysId != null) {
            alerts = alerts.stream()
                .filter(a -> paysId.equals(a.getContract().getPaysId()))
                .collect(Collectors.toList());
        }
        return alerts.stream().map(this::mapToAlertDto).collect(Collectors.toList());
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_ALERTS')")
    public LifecycleAlertDto acknowledgeAlert(Long alertId, Long userId) {
        EmployeeLifecycleAlert alert = alertRepo.findById(alertId)
            .orElseThrow(() -> new BusinessRuleException("D3-103", "Alerte introuvable."));
        alert.setIsAcknowledged(true);
        alert.setAcknowledgedBy(userId);
        alert.setAcknowledgedAt(OffsetDateTime.now());
        alertRepo.save(alert);
        return mapToAlertDto(alert);
    }

    @Transactional(readOnly = true)
    public ContractTypeConfigDto getConfig(Long paysId, String contractTypeCode) {
        return configRepo.findByPaysIdAndContractTypeCode(paysId, contractTypeCode)
            .map(this::mapToConfigDto)
            .orElseThrow(() -> new BusinessRuleException("D3-105",
                "Configuration introuvable pour pays=" + paysId + " type=" + contractTypeCode));
    }

    @PreAuthorize("hasPermission(null, 'ADMIN_ROLES')")
    public ContractTypeConfigDto updateConfig(Long configId, UpdateContractTypeConfigRequest dto, Long updatedBy) {
        ContractTypeConfig config = configRepo.findById(configId)
            .orElseThrow(() -> new BusinessRuleException("D3-105", "Configuration introuvable."));

        if (dto.getTrialPeriodDaysStandard()    != null) config.setTrialPeriodDaysStandard(dto.getTrialPeriodDaysStandard());
        if (dto.getTrialPeriodDaysManager()     != null) config.setTrialPeriodDaysManager(dto.getTrialPeriodDaysManager());
        if (dto.getTrialPeriodRenewable()       != null) config.setTrialPeriodRenewable(dto.getTrialPeriodRenewable());
        if (dto.getAlertDaysBeforeExpiry()      != null) config.setAlertDaysBeforeExpiry(dto.getAlertDaysBeforeExpiry());
        if (dto.getIndemnityRatePct()           != null) config.setIndemnityRatePct(dto.getIndemnityRatePct());
        if (dto.getIndemnityApplicable()        != null) config.setIndemnityApplicable(dto.getIndemnityApplicable());
        if (dto.getCivpMaxAge()                 != null) config.setCivpMaxAge(dto.getCivpMaxAge());
        if (dto.getCivpMaxDurationMonths()      != null) config.setCivpMaxDurationMonths(dto.getCivpMaxDurationMonths());
        if (dto.getCivpAnetiRequired()          != null) config.setCivpAnetiRequired(dto.getCivpAnetiRequired());
        if (dto.getStageMaxDurationMonths()     != null) config.setStageMaxDurationMonths(dto.getStageMaxDurationMonths());
        if (dto.getStageMinGratificationMonths()!= null) config.setStageMinGratificationMonths(dto.getStageMinGratificationMonths());

        config.setUpdatedAt(OffsetDateTime.now());
        config.setUpdatedBy(updatedBy);
        configRepo.save(config);

        logConfigChange(config, updatedBy);
        return mapToConfigDto(config);
    }

    // ── CIVP validation (D3-98, D3-105) ──────────────────────────────────────

    void validateCIVPEligibility(EmployeeProfile profile, CreateContractRequest dto, ContractTypeConfig config) {
        if (profile.getDateOfBirth() != null) {
            int age    = Period.between(profile.getDateOfBirth(), LocalDate.now()).getYears();
            int maxAge = config.getCivpMaxAge() != null ? config.getCivpMaxAge() : 30;
            if (age >= maxAge) {
                throw new BusinessRuleException("D3-98",
                    "Le CIVP est réservé aux primo-demandeurs d'emploi de moins de "
                    + maxAge + " ans. Âge actuel : " + age + " ans.");
            }
        }
        if (dto.getDateFinPrevue() == null) {
            throw new BusinessRuleException("D3-98",
                "La date de fin est obligatoire pour un CIVP.");
        }
        long months    = ChronoUnit.MONTHS.between(dto.getDateDebut(), dto.getDateFinPrevue());
        int maxMonths  = config.getCivpMaxDurationMonths() != null ? config.getCivpMaxDurationMonths() : 12;
        if (months > maxMonths) {
            throw new BusinessRuleException("D3-98",
                "La durée du CIVP ne peut pas dépasser " + maxMonths
                + " mois (durée demandée : " + months + " mois).");
        }
        if (Boolean.TRUE.equals(config.getCivpAnetiRequired())
                && (dto.getCivpAnetiReference() == null || dto.getCivpAnetiReference().isBlank())) {
            throw new BusinessRuleException("D3-98",
                "La référence ANETI est obligatoire pour un CIVP en Tunisie.");
        }
    }

    // ── Trial period calculation ───────────────────────────────────────────────

    LocalDate calculateTrialEndDate(LocalDate dateDebut, String contractType,
                                     boolean isManager, ContractTypeConfig config) {
        return switch (contractType) {
            case "CDI" -> {
                int days = isManager
                    ? (config.getTrialPeriodDaysManager() != null ? config.getTrialPeriodDaysManager() : 90)
                    : (config.getTrialPeriodDaysStandard() != null ? config.getTrialPeriodDaysStandard() : 30);
                yield dateDebut.plusDays(days);
            }
            case "CDD"  -> {
                int days = config.getTrialPeriodDaysStandard() != null ? config.getTrialPeriodDaysStandard() : 7;
                yield dateDebut.plusDays(days);
            }
            case "CIVP" -> dateDebut.plusMonths(1);
            default     -> null;  // STAGE/FREELANCE/DETACHEMENT have no trial period
        };
    }

    // ── Append-only transition log (D3-104) ───────────────────────────────────

    void logTransition(EmployeeContract contract, String statutAvant, String statutApres,
                        String actionCode, Long triggeredBy,
                        String commentaire, String documentReference) {
        try {
            EmployeeLifecycleTransition t = EmployeeLifecycleTransition.builder()
                .contract(contract)
                .employeeProfileId(contract.getEmployeeProfile().getId())
                .statutAvant(statutAvant)
                .statutApres(statutApres)
                .actionCode(actionCode)
                .triggeredByUserId(triggeredBy)
                .triggeredAt(OffsetDateTime.now())
                .commentaire(commentaire)
                .documentReference(documentReference)
                .build();
            transitionRepo.save(t);
        } catch (Exception e) {
            // NEVER throw — audit must not block business operations
            log.error("Failed to log lifecycle transition for contract {}: {}",
                contract.getId(), e.getMessage());
        }
    }

    // ── Notifications (D3-103) ────────────────────────────────────────────────

    @Async
    void triggerTransitionNotifications(EmployeeContract contract,
                                         String previousStatus, String newStatus,
                                         Long triggeredBy) {
        try {
            String employeeName = loadEmployeeName(contract.getEmployeeProfile().getId());
            String title = "Changement de statut collaborateur";
            String body  = employeeName + " : " + previousStatus + " → " + newStatus
                + " (" + contract.getContractTypeCode() + ")";

            Set<Long> notified = new HashSet<>();
            for (String perm : List.of("RH_VIEW_CONTRACTS", "RH_MANAGE_LIFECYCLE")) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    USERS_WITH_PERM_SQL, perm, contract.getPaysId());
                for (Map<String, Object> row : rows) {
                    Long uid   = ((Number) row.get("id")).longValue();
                    String email = (String) row.get("email");
                    if (!notified.add(uid)) continue;
                    try {
                        jdbc.update(INSERT_NOTIF_SQL, uid, "RH", title, body);
                    } catch (Exception ex) {
                        log.error("In-app notification failed for userId={}: {}", uid, ex.getMessage());
                    }
                    if (email != null && !email.isBlank()) {
                        try {
                            mailService.sendRoutedEmail(
                                List.of(email), List.of(), List.of(),
                                "[DAF360 RH] " + title, body);
                        } catch (Exception ex) {
                            log.warn("Email notification failed for userId={}: {}", uid, ex.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("triggerTransitionNotifications failed for contract {}: {}",
                contract.getId(), e.getMessage());
        }
    }

    String loadEmployeeName(Long employeeProfileId) {
        try {
            return jdbc.queryForObject(EMPLOYEE_NAME_SQL, String.class, employeeProfileId);
        } catch (Exception e) {
            return "Collaborateur";
        }
    }

    void logConfigChange(ContractTypeConfig config, Long updatedBy) {
        try {
            jdbc.update(
                "INSERT INTO [dbo].[audit_log] (user_id, action, entity_type, entity_id, new_value, timestamp) " +
                "VALUES (?, 'UPDATE_CONTRACT_TYPE_CONFIG', 'ContractTypeConfig', ?, ?, SYSDATETIMEOFFSET())",
                updatedBy, config.getId(),
                "pays=" + config.getPaysId() + " type=" + config.getContractTypeCode()
            );
        } catch (Exception e) {
            log.error("Config audit log failed: {}", e.getMessage());
        }
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    ContractDetailDto mapToDetailDto(EmployeeContract c) {
        return ContractDetailDto.builder()
            .id(c.getId())
            .employeeProfileId(c.getEmployeeProfile().getId())
            .paysId(c.getPaysId())
            .contractTypeCode(c.getContractTypeCode())
            .currentStatusCode(c.getCurrentStatusCode())
            .dateDebut(c.getDateDebut())
            .dateFinPrevue(c.getDateFinPrevue())
            .dateFinReelle(c.getDateFinReelle())
            .dateFinPeriodeEssai(c.getDateFinPeriodeEssai())
            .periodeEssaiRenouvelee(c.getPeriodeEssaiRenouvelee())
            .dateFinPeRenouvellement(c.getDateFinPeRenouvellement())
            .endReasonCode(c.getEndReasonCode())
            .endNotes(c.getEndNotes())
            .referenceContrat(c.getReferenceContrat())
            .civpAnetiReference(c.getCivpAnetiReference())
            .civpConventionDate(c.getCivpConventionDate())
            .stageEcole(c.getStageEcole())
            .stageTuteurId(c.getStageTuteurId())
            .stageConventionSignee(c.getStageConventionSignee())
            .freelanceTjm(c.getFreelanceTjm())
            .freelanceDevise(c.getFreelanceDevise())
            .freelanceSociete(c.getFreelanceSociete())
            .detachementEntiteOrigineId(c.getDetachementEntiteOrigineId())
            .detachementEntiteAccueilId(c.getDetachementEntiteAccueilId())
            .detachementRetourPrevu(c.getDetachementRetourPrevu())
            .cddRenouvellementCount(c.getCddRenouvellementCount())
            .cddContratParentId(c.getCddContratParent() != null ? c.getCddContratParent().getId() : null)
            .avenantParentId(c.getAvenantParent() != null ? c.getAvenantParent().getId() : null)
            .isActive(c.getIsActive())
            .isArchived(c.getIsArchived())
            .dossierLocked(c.getDossierLocked())
            .createdBy(c.getCreatedBy())
            .createdAt(c.getCreatedAt())
            .updatedAt(c.getUpdatedAt())
            .build();
    }

    ContractListDto mapToListDto(EmployeeContract c) {
        return ContractListDto.builder()
            .id(c.getId())
            .employeeProfileId(c.getEmployeeProfile().getId())
            .contractTypeCode(c.getContractTypeCode())
            .currentStatusCode(c.getCurrentStatusCode())
            .dateDebut(c.getDateDebut())
            .dateFinPrevue(c.getDateFinPrevue())
            .dateFinPeriodeEssai(c.getDateFinPeriodeEssai())
            .isActive(c.getIsActive())
            .dossierLocked(c.getDossierLocked())
            .referenceContrat(c.getReferenceContrat())
            .createdAt(c.getCreatedAt())
            .build();
    }

    ContractTransitionHistoryDto mapToHistoryDto(EmployeeLifecycleTransition t) {
        return ContractTransitionHistoryDto.builder()
            .id(t.getId())
            .contractId(t.getContract().getId())
            .employeeProfileId(t.getEmployeeProfileId())
            .statutAvant(t.getStatutAvant())
            .statutApres(t.getStatutApres())
            .actionCode(t.getActionCode())
            .triggeredByUserId(t.getTriggeredByUserId())
            .triggeredAt(t.getTriggeredAt())
            .commentaire(t.getCommentaire())
            .documentReference(t.getDocumentReference())
            .build();
    }

    LifecycleAlertDto mapToAlertDto(EmployeeLifecycleAlert a) {
        return LifecycleAlertDto.builder()
            .id(a.getId())
            .contractId(a.getContract().getId())
            .employeeProfileId(a.getEmployeeProfileId())
            .alertType(a.getAlertType())
            .alertDate(a.getAlertDate())
            .targetDate(a.getTargetDate())
            .recipients(a.getRecipients())
            .isSent(a.getIsSent())
            .sentAt(a.getSentAt())
            .isAcknowledged(a.getIsAcknowledged())
            .acknowledgedBy(a.getAcknowledgedBy())
            .acknowledgedAt(a.getAcknowledgedAt())
            .build();
    }

    ContractTypeConfigDto mapToConfigDto(ContractTypeConfig c) {
        return ContractTypeConfigDto.builder()
            .id(c.getId())
            .paysId(c.getPaysId())
            .contractTypeCode(c.getContractTypeCode())
            .trialPeriodDaysStandard(c.getTrialPeriodDaysStandard())
            .trialPeriodDaysManager(c.getTrialPeriodDaysManager())
            .trialPeriodRenewable(c.getTrialPeriodRenewable())
            .alertDaysBeforeExpiry(c.getAlertDaysBeforeExpiry())
            .indemnityRatePct(c.getIndemnityRatePct())
            .indemnityApplicable(c.getIndemnityApplicable())
            .civpMaxAge(c.getCivpMaxAge())
            .civpMaxDurationMonths(c.getCivpMaxDurationMonths())
            .civpAnetiRequired(c.getCivpAnetiRequired())
            .stageMaxDurationMonths(c.getStageMaxDurationMonths())
            .stageMinGratificationMonths(c.getStageMinGratificationMonths())
            .build();
    }
}
