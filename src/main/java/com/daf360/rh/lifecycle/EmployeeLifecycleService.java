package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.*;
import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.*;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.notification.NotificationEntityType;
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
    /** contract_type_code stores the CONTRACT_TYPE list id; rules follow the value's nature. */
    private final com.daf360.rh.lists.ContractTypeRefs contractTypeRefs;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;
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

        // dto.contractTypeCode is a CONTRACT_TYPE code (CDI…) or list id; the contract stores the
        // id, and every rule below follows the value's nature (a « contrat » set to CDI → CDI rules).
        String contractTypeId = contractTypeRefs.toStored(dto.getContractTypeCode(), dto.getPaysId());
        String nature = contractTypeRefs.natureOf(contractTypeId, dto.getPaysId());

        ContractTypeConfig config = configRepo
            .findForNature(dto.getPaysId(), nature)
            .orElseThrow(() -> new BusinessRuleException("D3-95",
                "Configuration de contrat introuvable pour ce pays et type de contrat."));

        String initialStatus = switch (nature) {
            case "STAGE"     -> "CONVENTION_SIGNEE";
            case "FREELANCE" -> "SOURCING_PRESTATAIRE";
            default          -> "RECRUTEMENT";
        };

        if ("CIVP".equals(nature)) {
            validateCIVPEligibility(profile, dto, config);
        }

        if ("CDD".equals(nature) && dto.getCddContratParentId() != null) {
            EmployeeContract parent = contractRepo.findById(dto.getCddContratParentId())
                .orElseThrow(() -> new BusinessRuleException("D3-97", "Contrat CDD parent introuvable."));
            if (parent.getCddRenouvellementCount() >= 1) {
                throw new BusinessRuleException("D3-97",
                    "Un CDD ne peut être renouvelé qu'une seule fois.");
            }
        }

        // A date RH typed (the onboarding's « fin de période d'essai ») wins over the computed
        // one: it is the agreed date, and the trial-end alert must announce that one.
        LocalDate trialEnd = dto.isNoTrialPeriod() ? null
            : dto.getDateFinPeriodeEssai() != null ? dto.getDateFinPeriodeEssai()
            : calculateTrialEndDate(dto.getDateDebut(), nature, dto.isManagerProfile(), config);

        ResolvedNotice notice = resolveNoticePeriod(dto, profile);

        EmployeeContract contract = EmployeeContract.builder()
            .employeeProfile(profile)
            .paysId(dto.getPaysId())
            .contractTypeId(contractTypeId)
            .currentStatusCode(initialStatus)
            .dateDebut(dto.getDateDebut())
            .dateFinPrevue(dto.getDateFinPrevue())
            .dateFinPeriodeEssai(trialEnd)
            .noticePeriodDays(notice.days())
            .noticePeriodSource(notice.source())
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

        // No alert planning here any more: LifecycleAlertJob derives due alerts from the
        // contract's dates on every run.

        return mapToDetailDto(contract);
    }

    // ── Transition state ──────────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto transitionState(Long contractId, TransitionRequest dto, Long triggeredBy) {
        EmployeeContract contract = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-96", "Contrat introuvable."));

        if (!stateMachine.isTransitionAllowed(
                natureOf(contract),
                contract.getCurrentStatusCode(),
                dto.getNewStatus())) {
            throw new BusinessRuleException("D3-96",
                "Transition non autorisée : " + contract.getCurrentStatusCode()
                + " → " + dto.getNewStatus()
                + " pour contrat type " + natureOf(contract) + ".");
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

        if (!"CDD".equals(natureOf(contract))) {
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
            p.setContractEndDate(dto.getNewDateFin());
            profileRepo.save(p);
        });

        // The new end date is a new alert occurrence (the ledger is keyed by target date), so
        // LifecycleAlertJob announces it without anything to plan here.

        return mapToDetailDto(contract);
    }

    // ── CDD: convert to CDI ───────────────────────────────────────────────────

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
    public ContractDetailDto convertToCDI(Long contractId, ConvertToCDIRequest dto, Long convertedBy) {
        EmployeeContract cdd = contractRepo.findById(contractId)
            .orElseThrow(() -> new BusinessRuleException("D3-97", "Contrat introuvable."));

        if (!"CDD".equals(natureOf(cdd))) {
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

    // ── Profile → contract date sync ──────────────────────────────────────────

    /**
     * Copies the end dates edited on the profile onto the profile's current, active contract.
     *
     * The profile form edits employee_profiles.contract_end_date / probation_end_date, but the
     * alerts read employee_contracts. Without this an HR correction on the profile left the
     * alert announcing the old date — or nothing. Null means "not sent", never "clear".
     * Security: the caller (profile update) is already permission-guarded.
     */
    public void syncContractDatesFromProfile(Long contractId, LocalDate contractEnd, LocalDate probationEnd) {
        if (contractId == null || (contractEnd == null && probationEnd == null)) return;
        contractRepo.findById(contractId)
            .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
            .ifPresent(c -> {
                boolean changed = false;
                if (contractEnd != null && !contractEnd.equals(c.getDateFinPrevue())) {
                    c.setDateFinPrevue(contractEnd);
                    changed = true;
                }
                // Only while the trial is still running: once validated, the date is history.
                if (probationEnd != null
                        && List.of("RECRUTEMENT", "PERIODE_ESSAI").contains(c.getCurrentStatusCode())
                        && !probationEnd.equals(c.getDateFinPeriodeEssai())) {
                    c.setDateFinPeriodeEssai(probationEnd);
                    changed = true;
                }
                if (changed) {
                    c.setUpdatedAt(OffsetDateTime.now());
                    contractRepo.save(c);
                }
            });
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
        // Accepts a code or a list id; the config is per nature, borrowed from Tunisie if missing.
        return configRepo.findForNature(paysId, contractTypeRefs.natureOf(contractTypeCode, paysId))
            .map(this::mapToConfigDto)
            .orElseThrow(() -> new BusinessRuleException("D3-105",
                "Configuration introuvable pour pays=" + paysId + " type=" + contractTypeCode));
    }

    /** Every configured contract type of one pays — the admin screen's grid. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasPermission(null, 'ADMIN_ROLES')")
    public List<ContractTypeConfigDto> listConfigs(Long paysId) {
        return configRepo.findByPaysId(paysId).stream()
            .sorted(Comparator.comparing(ContractTypeConfig::getContractTypeCode))
            .map(this::mapToConfigDto)
            .collect(Collectors.toList());
    }

    /**
     * Creates the row for a (pays, contract type) that has none.
     *
     * Only pays_id=1 was ever seeded, so every other entity had no row: contract creation
     * threw D3-95 there and no expiry alert could be configured. The admin screen offers to
     * create the missing types from here, with the entity defaults unless values are given.
     */
    @PreAuthorize("hasPermission(null, 'ADMIN_ROLES')")
    public ContractTypeConfigDto createConfig(Long paysId, String contractTypeCode,
                                              UpdateContractTypeConfigRequest dto, Long createdBy) {
        if (paysId == null || contractTypeCode == null || contractTypeCode.isBlank()) {
            throw new BusinessRuleException("D3-105", "Pays et type de contrat obligatoires.");
        }
        // Rows are keyed by nature (CDI, CDD…): a list value (id or code) is reduced to its
        // nature, so the row is the one createContract / the alert job will actually read.
        String requested = contractTypeCode.trim().toUpperCase();
        String code = com.daf360.rh.lists.ContractTypeRefs.NATURES.contains(requested) ? requested
            : contractTypeRefs.find(contractTypeCode.trim(), paysId)
                .map(com.daf360.rh.lists.ContractTypeRefs::natureOf)
                .orElseThrow(() -> new BusinessRuleException("D3-105",
                    "Type de contrat inconnu : " + contractTypeCode + "."));
        if (configRepo.findByPaysIdAndContractTypeCode(paysId, code).isPresent()) {
            throw new BusinessRuleException("D3-105",
                "Une configuration existe déjà pour ce pays et ce type de contrat.");
        }
        ContractTypeConfig config = ContractTypeConfig.builder()
            .paysId(paysId)
            .contractTypeCode(code)
            .trialPeriodDaysStandard("CDI".equals(code) ? 90 : 0)
            .trialPeriodDaysManager("CDI".equals(code) ? 180 : 0)
            .updatedBy(createdBy)
            .build();
        config = configRepo.save(config);
        // Same field-by-field rules as an edit, so a create can carry the lead times.
        return dto != null ? updateConfig(config.getId(), dto, createdBy) : mapToConfigDto(config);
    }

    @PreAuthorize("hasPermission(null, 'ADMIN_ROLES')")
    public ContractTypeConfigDto updateConfig(Long configId, UpdateContractTypeConfigRequest dto, Long updatedBy) {
        ContractTypeConfig config = configRepo.findById(configId)
            .orElseThrow(() -> new BusinessRuleException("D3-105", "Configuration introuvable."));

        validateLeadDays(dto.getAlertDaysBeforeExpiry());
        validateLeadDays(dto.getAlertDaysBeforeTrialEnd());

        if (dto.getTrialPeriodDaysStandard()    != null) config.setTrialPeriodDaysStandard(dto.getTrialPeriodDaysStandard());
        if (dto.getTrialPeriodDaysManager()     != null) config.setTrialPeriodDaysManager(dto.getTrialPeriodDaysManager());
        if (dto.getTrialPeriodRenewable()       != null) config.setTrialPeriodRenewable(dto.getTrialPeriodRenewable());
        if (dto.getAlertDaysBeforeExpiry()      != null) config.setAlertDaysBeforeExpiry(dto.getAlertDaysBeforeExpiry());
        if (dto.getAlertDaysBeforeTrialEnd()    != null) config.setAlertDaysBeforeTrialEnd(dto.getAlertDaysBeforeTrialEnd());
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

    /** Also enforced by the DTO's @Min/@Max; repeated here because createConfig calls in directly. */
    private static void validateLeadDays(Integer days) {
        if (days != null && (days < 0 || days > LifecycleAlertJob.MAX_LEAD_DAYS)) {
            throw new BusinessRuleException("D3-105",
                "Le délai d'alerte doit être compris entre 0 et " + LifecycleAlertJob.MAX_LEAD_DAYS + " jours.");
        }
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

    // ── Préavis resolution (V69) ──────────────────────────────────────────────

    /** The préavis to freeze on the contract, and where it came from. */
    record ResolvedNotice(Integer days, String source) {
        static final ResolvedNotice UNKNOWN = new ResolvedNotice(null, null);
    }

    /**
     * The préavis from the offer the candidate ACCEPTED — not the latest round.
     *
     * Since V98 job_offers holds one row per negotiation round, so this needed a rule for
     * which round to read, and "the most recent" is the wrong one: a round drafted or
     * renegotiated after acceptance would retroactively change the préavis frozen onto a
     * signed contract. The accepted round is the one the candidate actually agreed to, and
     * it is immutable once decided.
     *
     * A candidate hired without going through an offer (the direct-hire path) matches
     * nothing here and falls through to the grade default below, exactly as before.
     */
    private static final String OFFER_NOTICE_SQL =
        "SELECT TOP 1 jo.notice_period_days " +
        "FROM [dbo].[employee_profiles] ep " +
        "JOIN [dbo].[job_offers] jo ON jo.candidate_id = ep.candidate_id " +
        "WHERE ep.id = ? AND jo.notice_period_days IS NOT NULL " +
        "  AND jo.status = 'ACCEPTED' " +
        "ORDER BY jo.decided_at DESC, jo.id DESC";

    private static final String GRADE_NOTICE_SQL =
        "SELECT g.notice_period_days " +
        "FROM [dbo].[employee_profiles] ep " +
        "JOIN [dbo].[grades] g ON g.id = ep.grade_id " +
        "WHERE ep.id = ?";

    /**
     * Resolves the préavis once, at creation time: explicit request value → the candidate's
     * offer → the employee's grade default → unknown.
     *
     * The request wins outright (including a deliberate 0) because that is RH confirming the
     * figure on the Contrat step, which is the last word by design. Nothing here is
     * recomputed later — see EmployeeContract.noticePeriodDays.
     */
    ResolvedNotice resolveNoticePeriod(CreateContractRequest dto, EmployeeProfile profile) {
        if (dto.getNoticePeriodDays() != null) {
            return new ResolvedNotice(dto.getNoticePeriodDays(), "MANUAL");
        }
        Integer fromOffer = queryNotice(OFFER_NOTICE_SQL, profile.getId());
        if (fromOffer != null) return new ResolvedNotice(fromOffer, "NEGOTIATED");

        Integer fromGrade = queryNotice(GRADE_NOTICE_SQL, profile.getId());
        if (fromGrade != null) return new ResolvedNotice(fromGrade, "GRADE_DEFAULT");

        // Loud, because a contract with no préavis makes the offboarding exit date
        // unknowable and nothing downstream can invent one.
        log.warn("Contract for profile {} created with NO préavis: no value supplied, no offer "
                 + "figure, and its grade has no default", profile.getId());
        return ResolvedNotice.UNKNOWN;
    }

    /** Never throws: a missing column or absent row must not block contract creation. */
    private Integer queryNotice(String sql, Long profileId) {
        try {
            List<Integer> rows = jdbc.queryForList(sql, Integer.class, profileId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.debug("Préavis lookup failed for profile {}: {}", profileId, ex.getMessage());
            return null;
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

    /**
     * Raises EMPLOYEE_STATUS_CHANGED through the routing engine.
     *
     * The two permissions this used to query by hand — RH_VIEW_CONTRACTS and
     * RH_MANAGE_LIFECYCLE — are now the rule's recipients (V92), so the audience and the
     * de-duplication are unchanged while the wording and channels become admin-editable.
     * @Async stays: the engine is async too, but a caller must not wait on either.
     */
    @Async
    void triggerTransitionNotifications(EmployeeContract contract,
                                         String previousStatus, String newStatus,
                                         Long triggeredBy) {
        try {
            Long profileId = contract.getEmployeeProfile().getId();
            notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
                .eventCode("EMPLOYEE_STATUS_CHANGED")
                .paysId(contract.getPaysId())
                .entityType(NotificationEntityType.EMPLOYEE_PROFILE)
                .entityId(profileId)
                .templateVars(Map.of(
                    "employeeName",   loadEmployeeName(profileId),
                    "previousStatus", previousStatus != null ? previousStatus : "",
                    "newStatus",      newStatus != null ? newStatus : "",
                    "contractType",   contractTypeLabel(contract)))
                .build());
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

    /** Lifecycle nature (CDI, CDD…) of a contract — what the rules and the state machine key on. */
    String natureOf(EmployeeContract c) {
        return contractTypeRefs.natureOf(c.getContractTypeId(), c.getPaysId());
    }

    /** The contract type as people know it (« contrat », « CDI — Durée indéterminée »…). */
    String contractTypeLabel(EmployeeContract c) {
        return contractTypeRefs.find(c.getContractTypeId(), c.getPaysId())
            .map(com.daf360.rh.lists.ConfigurableListValue::getLabelFr)
            .orElseGet(() -> c.getContractTypeId() != null ? c.getContractTypeId() : "");
    }

    ContractDetailDto mapToDetailDto(EmployeeContract c) {
        return ContractDetailDto.builder()
            .id(c.getId())
            .employeeProfileId(c.getEmployeeProfile().getId())
            .paysId(c.getPaysId())
            .contractTypeCode(natureOf(c))
            .contractTypeId(contractTypeRefs.idOf(c.getContractTypeId(), c.getPaysId()))
            .contractTypeLabel(contractTypeLabel(c))
            .currentStatusCode(c.getCurrentStatusCode())
            .dateDebut(c.getDateDebut())
            .dateFinPrevue(c.getDateFinPrevue())
            .dateFinReelle(c.getDateFinReelle())
            .dateFinPeriodeEssai(c.getDateFinPeriodeEssai())
            .periodeEssaiRenouvelee(c.getPeriodeEssaiRenouvelee())
            .dateFinPeRenouvellement(c.getDateFinPeRenouvellement())
            .noticePeriodDays(c.getNoticePeriodDays())
            .noticePeriodSource(c.getNoticePeriodSource())
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
            .contractTypeCode(natureOf(c))
            .contractTypeId(contractTypeRefs.idOf(c.getContractTypeId(), c.getPaysId()))
            .contractTypeLabel(contractTypeLabel(c))
            .currentStatusCode(c.getCurrentStatusCode())
            .dateDebut(c.getDateDebut())
            .dateFinPrevue(c.getDateFinPrevue())
            .dateFinPeriodeEssai(c.getDateFinPeriodeEssai())
            .noticePeriodDays(c.getNoticePeriodDays())
            .noticePeriodSource(c.getNoticePeriodSource())
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
            .alertDaysBeforeTrialEnd(c.getAlertDaysBeforeTrialEnd())
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
