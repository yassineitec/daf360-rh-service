package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.common.GenderNormalizer;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.ItAsset;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.lifecycle.CreateContractRequest;
import com.daf360.rh.dto.onboarding.CompleteProfileRequest;
import com.daf360.rh.dto.onboarding.CompletionResult;
import com.daf360.rh.dto.onboarding.OnboardingFormResponse;
import com.daf360.rh.dto.onboarding.OnboardingKpiDto;
import com.daf360.rh.dto.onboarding.OnboardingListItem;
import com.daf360.rh.dto.onboarding.OnboardingRecruitmentDto;
import com.daf360.rh.dto.onboarding.RegimeSummary;
import com.daf360.rh.dto.onboarding.SaveDraftRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OnboardingService {

    private final CandidateRepository        candidateRepo;
    private final ItProvisioningRepository   itProvisioningRepo;
    private final EmployeeProfileRepository  profileRepo;
    private final WorkingTimeRegimeRepository regimeRepo;
    private final WorkflowInstanceService    workflowInstanceService;
    private final MailService                mailService;
    private final AuditService               auditService;
    private final AppProperties              appProperties;
    private final JdbcTemplate               jdbc;
    private final ObjectMapper               objectMapper;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;
    private final com.daf360.rh.security.TenantService tenantService;
    // Dimension repos for FK resolution (V23)
    private final com.daf360.rh.repository.GradeRepository        gradeRepo;
    private final com.daf360.rh.repository.DisciplineRepository   disciplineRepo;
    private final com.daf360.rh.repository.NogLevelRepository     nogLevelRepo;
    private final com.daf360.rh.repository.HrDepartmentRepository deptRepo;
    private final com.daf360.rh.repository.NationalityRepository  natRepo;
    private final com.daf360.rh.repository.BankRepository         bankRepo;
    private final com.daf360.rh.lifecycle.ContractTypeBridge      contractTypeBridge;
    /**
     * Contract creation at completion (V69). Safe injection: EmployeeLifecycleService knows
     * nothing about onboarding, so there is no cycle.
     */
    private final com.daf360.rh.lifecycle.EmployeeLifecycleService lifecycleService;
    private final EmployeeDocumentService                          documentService;
    private final ItAssetAssignmentService                         assetAssignmentService;

    // ─── Valid statuses for the onboarding pending list ──────────────────────
    private static final Set<CandidateStatus> PENDING_STATUSES =
            EnumSet.of(CandidateStatus.EMAIL_RECEIVED, CandidateStatus.HR_IN_PROGRESS);

    // ─── Valid statuses for saving a draft ───────────────────────────────────
    private static final Set<CandidateStatus> DRAFT_ALLOWED_STATUSES =
            EnumSet.of(CandidateStatus.PENDING, CandidateStatus.ACCEPTED,
                       CandidateStatus.IT_IN_PROGRESS, CandidateStatus.EMAIL_RECEIVED,
                       CandidateStatus.HR_IN_PROGRESS);

    // ─── Required document slot labels ───────────────────────────────────────
    private static final List<String> REQUIRED_DOCUMENT_SLOTS = List.of(
            "CIN/Passeport",
            "Contrat de travail",
            "Photo d'identité",
            "RIB bancaire",
            "CNSS",
            "Certificat de scolarité (si applicable)"
    );

    // =========================================================================
    // getPendingList
    // =========================================================================

    @Transactional(readOnly = true)
    public List<OnboardingListItem> getPendingList() {
        Long paysId = tenantService.getEffectivePaysId();
        List<Candidate> candidates = paysId != null
                ? candidateRepo.findByStatusInAndPaysId(PENDING_STATUSES, paysId)
                : candidateRepo.findByStatusIn(PENDING_STATUSES);

        return candidates.stream()
                .map(c -> {
                    ItProvisioning prov = itProvisioningRepo.findByCandidateId(c.getId())
                            .orElse(null);
                    return toListItem(c, prov);
                })
                .sorted(Comparator.comparing(
                        OnboardingListItem::getMs365EmailCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // getKpi
    // =========================================================================

    @Transactional(readOnly = true)
    public OnboardingKpiDto getKpi() {
        long pendingCount = candidateRepo.countByStatusIn(PENDING_STATUSES);

        Long createdTodayRaw = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[employee_profiles] " +
                "WHERE CAST(created_at AS DATE) = CAST(GETDATE() AS DATE) AND deleted = 0",
                Long.class);
        long profilesCreatedToday = createdTodayRaw != null ? createdTodayRaw : 0L;

        Long incompleteRaw = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[employee_profiles] " +
                "WHERE deleted = 0 " +
                "  AND lifecycle_status NOT IN ('TERMINATED', 'ARCHIVED') " +
                "  AND (hire_date IS NULL OR contract_type IS NULL OR contract_type = '' " +
                "       OR cnss_number IS NULL OR cnss_number = '' " +
                "       OR rib IS NULL OR rib = '')",
                Long.class);
        long incompleteProfiles = incompleteRaw != null ? incompleteRaw : 0L;

        Double avgCreationMinutes = jdbc.queryForObject(
                "SELECT AVG(CAST(DATEDIFF(MINUTE, ip.ms365_email_created_at, ep.onboarding_completed_at) AS FLOAT)) " +
                "FROM [dbo].[it_provisioning] ip " +
                "JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = ip.candidate_id " +
                "WHERE ep.onboarding_completed = 1 " +
                "  AND ep.onboarding_completed_at >= DATEADD(day, -30, SYSDATETIMEOFFSET()) " +
                "  AND ip.ms365_email_created_at IS NOT NULL " +
                "  AND ep.deleted = 0",
                Double.class);

        return OnboardingKpiDto.builder()
                .pendingCount(pendingCount)
                .profilesCreatedToday(profilesCreatedToday)
                .incompleteProfiles(incompleteProfiles)
                .avgCreationMinutes(avgCreationMinutes)
                .build();
    }

    // =========================================================================
    // getOnboardingForm
    // =========================================================================

    @Transactional(readOnly = true)
    public OnboardingFormResponse getOnboardingForm(Long candidateId) {
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));

        ItProvisioning prov = itProvisioningRepo.findByCandidateId(candidateId)
                .orElse(null);

        List<WorkingTimeRegime> regimes =
                regimeRepo.findByPaysIdAndIsActiveTrue(candidate.getPaysId());

        // Load draft
        SaveDraftRequest draft    = null;
        OffsetDateTime   draftSavedAt = null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT draft_data, saved_at FROM [dbo].[onboarding_drafts] WHERE candidate_id = ?",
                candidateId);
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            String json = (String) row.get("draft_data");
            draftSavedAt = row.get("saved_at") instanceof OffsetDateTime
                    ? (OffsetDateTime) row.get("saved_at")
                    : null;
            if (draftSavedAt == null && row.get("saved_at") != null) {
                // JDBC may return a java.time type or a sql type — convert safely
                Object raw = row.get("saved_at");
                try {
                    draftSavedAt = OffsetDateTime.parse(raw.toString());
                } catch (Exception ex) {
                    log.warn("Could not parse draft saved_at value '{}': {}", raw, ex.getMessage());
                }
            }
            draft = deserializeDraft(json);
        }

        // Load existing employee profile if it exists (may have been pre-filled from Profiles page)
        EmployeeProfile existingProfile = (prov != null && prov.getUserId() != null)
                ? profileRepo.findByUserId(prov.getUserId()).orElse(null)
                : null;

        return buildFormResponse(candidate, prov, regimes, draft, draftSavedAt, existingProfile);
    }

    // =========================================================================
    // saveDraft
    // =========================================================================

    public Map<String, Object> saveDraft(Long candidateId, SaveDraftRequest dto, Long hrOfficerId) {
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));

        if (!DRAFT_ALLOWED_STATUSES.contains(candidate.getStatus())) {
            throw new AppException(ErrorCode.ONBOARDING_STATUS_INVALID);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize draft for candidateId={}: {}", candidateId, ex.getMessage());
            throw new AppException(ErrorCode.INTERNAL_ERROR, "Échec de la sérialisation du brouillon");
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[onboarding_drafts] WHERE candidate_id = ?",
                Integer.class, candidateId);
        if (count != null && count > 0) {
            jdbc.update(
                    "UPDATE [dbo].[onboarding_drafts] SET draft_data = ?, saved_by = ?, saved_at = SYSDATETIMEOFFSET() WHERE candidate_id = ?",
                    json, hrOfficerId, candidateId);
        } else {
            jdbc.update(
                    "INSERT INTO [dbo].[onboarding_drafts] (candidate_id, draft_data, saved_by, saved_at) VALUES (?, ?, ?, SYSDATETIMEOFFSET())",
                    candidateId, json, hrOfficerId);
        }

        if (candidate.getStatus() != CandidateStatus.HR_IN_PROGRESS) {
            candidate.setStatus(CandidateStatus.HR_IN_PROGRESS);
        }
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepo.save(candidate);

        OffsetDateTime savedAt = OffsetDateTime.now();
        return Map.of("candidateId", candidateId, "savedAt", savedAt);
    }

    // =========================================================================
    // completeEmployeeProfile
    // =========================================================================

    public CompletionResult completeEmployeeProfile(Long candidateId,
                                                    CompleteProfileRequest dto,
                                                    Long hrOfficerId) {
        // STEP 0 — Validate candidate
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
        if (!PENDING_STATUSES.contains(candidate.getStatus())) {
            throw new AppException(ErrorCode.ONBOARDING_STATUS_INVALID);
        }

        // STEP 0b — Validate provisioning
        ItProvisioning prov = itProvisioningRepo.findByCandidateId(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.IT_PROVISIONING_NOT_FOUND));
        if (prov.getUserId() == null) {
            throw new AppException(ErrorCode.ONBOARDING_USER_NOT_CREATED);
        }

        // STEP 0c — Guard: block only if onboarding already fully completed; allow re-run for incomplete attempts
        profileRepo.findByCandidateId(candidateId).ifPresent(existing -> {
            if (Boolean.TRUE.equals(existing.getOnboardingCompleted())) {
                throw new AppException(ErrorCode.ONBOARDING_PROFILE_EXISTS);
            }
            log.warn("Re-running onboarding for candidateId={} — previous attempt was incomplete, overwriting.", candidateId);
        });

        // STEP 1 — Find existing profile by userId (may be a batch-created minimal profile)
        //          If found → enrich it. If not → create a new one.
        EmployeeProfile profile = profileRepo.findByUserId(prov.getUserId())
                .orElseGet(() -> EmployeeProfile.builder()
                        .userId(prov.getUserId())
                        .paysId(candidate.getPaysId())
                        .deleted(false)
                        .createdAt(OffsetDateTime.now())
                        .build());

        // Apply all fields from the onboarding form
        profile.setCandidateId(candidateId);
        profile.setLifecycleStatus(LifecycleStatus.PRE_ONBOARDING);
        profile.setOnboardingCompleted(false);
        profile.setPaysId(candidate.getPaysId());
        profile.setUpdatedAt(OffsetDateTime.now());
        // Employment
        profile.setHireDate(dto.getHireDate());
        profile.setContractType(dto.getContractType());
        profile.setContractEndDate(dto.getContractEndDate());
        profile.setProbationEndDate(dto.getProbationEndDate());
        profile.setIsOnProbation(dto.getIsOnProbation());
        // The salary RH confirmed on the Contrat step. Before this, the négociated figure
        // reached no profile field at all and had to be retyped on the profile page later.
        // Guarded so a wizard submitted without the step does not blank an existing value.
        if (dto.getAgreedNetSalary() != null) {
            profile.setSalaireNetRh(dto.getAgreedNetSalary());
        }
        // Regime
        profile.setRegimeTemplateId(dto.getRegimeTemplateId());
        profile.setRegimeStartDate(dto.getRegimeStartDate());
        // Personal & Social
        profile.setCnssNumber(dto.getCnssNumber());
        profile.setCnssAffiliationDate(dto.getCnssAffiliationDate());
        profile.setMaritalStatus(dto.getMaritalStatus());
        profile.setNumberOfChildren(dto.getNumberOfChildren());
        profile.setDateOfBirth(dto.getDateOfBirth());
        profile.setGender(GenderNormalizer.normalize(dto.getGender()));
        profile.setNationalId(dto.getNationalId());
        profile.setPassportNumber(dto.getPassportNumber());
        // Contact
        profile.setPhone(candidate.getPhone());
        profile.setPersonalAddress(dto.getPersonalAddress());
        // Bank / RIB
        profile.setBankAccountNumber(dto.getBankAccountNumber());
        profile.setRib(dto.getRib());
        profile.setIban(dto.getIban());
        profile.setSocialSecurityNumber(dto.getSocialSecurityNumber());
        profile.setTaxId(dto.getTaxId());
        // Emergency contact
        profile.setEmergencyContactName(dto.getEmergencyContactName());
        profile.setEmergencyContactRelation(dto.getEmergencyContactRelation());
        profile.setEmergencyContactPhone(dto.getEmergencyContactPhone());

        // Dimension FK IDs (V23 — grade, discipline, nog, department, nationality, bank)
        if (dto.getGradeId()       != null) gradeRepo.findById(dto.getGradeId()).ifPresent(profile::setGrade);
        if (dto.getDisciplineId()  != null) disciplineRepo.findById(dto.getDisciplineId()).ifPresent(profile::setDiscipline);
        if (dto.getNogLevelId()    != null) nogLevelRepo.findById(dto.getNogLevelId()).ifPresent(profile::setNogLevel);
        if (dto.getDepartmentId()  != null) deptRepo.findById(dto.getDepartmentId()).ifPresent(profile::setDepartment);
        if (dto.getNationalityId() != null) natRepo.findById(dto.getNationalityId()).ifPresent(profile::setNationality);
        if (dto.getBankId()        != null) bankRepo.findById(dto.getBankId()).ifPresent(profile::setBank);

        EmployeeProfile saved = profileRepo.save(profile);

        // STEP 2 — Update candidate to HIRED
        candidate.setStatus(CandidateStatus.HIRED);
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepo.save(candidate);

        // STEP 2b — Create the lifecycle contract, carrying the agreed préavis.
        //
        // This used to be skipped entirely: onboarding wrote contract_type / hire_date onto the
        // PROFILE and never created an employee_contracts row, so a wizard-completed employee
        // had no contract, no state machine and nowhere for the préavis to live. Only the
        // candidates-page hire flow created one.
        createLifecycleContract(saved, candidate, dto, hrOfficerId);

        // STEP 2c — Attach the signed contract PDF, staged against the candidate while the
        // profile did not yet exist.
        linkContractDocument(saved, dto, hrOfficerId);

        // STEP 2d — Open the IT equipment ledger for this employee (V76).
        //
        // The other end of the same hook in ItProvisioningService.completeProvisioning: that
        // one is a no-op when IT finishes BEFORE the profile exists, which is the usual
        // order. Here the profile has just been created, so this is where those rows land.
        // Idempotent on (provisioning, asset type) — running both is safe.
        try {
            assetAssignmentService.seedFromProvisioning(prov.getId(), hrOfficerId);
        } catch (Exception ex) {
            log.warn("IT asset ledger seeding failed for candidateId={}: {}",
                    candidateId, ex.getMessage());
        }

        // STEP 3 — Delete onboarding draft
        jdbc.update("DELETE FROM [dbo].[onboarding_drafts] WHERE candidate_id = ?", candidateId);

        // STEP 4 — Create workflow instance.
        // triggered_by is NOT NULL + FK → Users(id); hrOfficerId is null when the request
        // carries no valid JWT, so fall back to the (guaranteed non-null) provisioned user id.
        Long triggeredBy = hrOfficerId != null ? hrOfficerId : prov.getUserId();
        Long workflowId = workflowInstanceService.createOnboardingInstance(
                saved.getId(), triggeredBy, candidate.getPaysId(), dto.getHireDate());

        // STEP 5 — Send welcome email (non-fatal)
        try {
            mailService.sendWelcomeEmail(
                    prov.getMs365Email(),
                    candidate.getFirstName(),
                    prov.getMs365Email(),
                    appProperties.getPortalUrl());
        } catch (Exception ex) {
            log.error("Failed to send welcome email to {} for candidateId={}: {}",
                    prov.getMs365Email(), candidateId, ex.getMessage());
        }

        // STEP 6 — In-app notification to new employee (non-fatal)
        notificationRoutingService.resolveAndDispatch(
            RoutingContext.builder()
                .eventCode("ONBOARDING_COMPLETED")
                .paysId(candidate.getPaysId())
                .subjectUserId(prov.getUserId())
                // The new employee is the SUBJECT: the rule targets them via a SUBJECT
                // recipient, so an admin can add RH or the manager alongside them.
                .templateVars(Map.of(
                    "firstName",      candidate.getFirstName(),
                    "candidateName",  candidate.getFirstName() + " " + candidate.getLastName(),
                    "ms365Email",     prov.getMs365Email() != null ? prov.getMs365Email() : ""
                ))
                .build()
        );

        // STEP 7 — Mark onboarding as COMPLETE and activate the profile
        saved.setOnboardingCompleted(true);
        saved.setOnboardingCompletedAt(OffsetDateTime.now());
        saved.setLifecycleStatus(LifecycleStatus.ACTIVE);
        saved.setUpdatedAt(OffsetDateTime.now());
        saved = profileRepo.save(saved);
        log.info("Onboarding completed: profileId={} userId={} now ACTIVE", saved.getId(), saved.getUserId());

        // STEP 8 — Audit log (hrOfficerId is null when the request carries no valid JWT)
        auditService.log(
                hrOfficerId != null ? hrOfficerId.toString() : "SYSTEM",
                "COMPLETE_ONBOARDING_PROFILE",
                "EMPLOYEE_PROFILE",
                saved.getId(),
                null,
                "candidateId=" + candidateId);

        return CompletionResult.builder()
                .employeeProfileId(saved.getId())
                .candidateId(candidateId)
                .userId(prov.getUserId())
                .workflowInstanceId(workflowId)
                .ms365Email(prov.getMs365Email())
                .message("Dossier complété avec succès. Email de bienvenue envoyé à " + prov.getMs365Email())
                .build();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private OnboardingListItem toListItem(Candidate c, ItProvisioning prov) {
        return OnboardingListItem.builder()
                .candidateId(c.getId())
                .candidateFullName(c.getFirstName() + " " + c.getLastName())
                .appliedPosition(c.getAppliedPosition())
                .paysId(c.getPaysId())
                .expectedStartDate(c.getExpectedStartDate())
                .candidateStatus(c.getStatus())
                .ms365Email(prov != null ? prov.getMs365Email() : null)
                .itProvisioningStatus(prov != null ? prov.getStatus() : null)
                .ms365EmailCreatedAt(prov != null ? prov.getMs365EmailCreatedAt() : null)
                .itProvisioningId(prov != null ? prov.getId() : null)
                .gender(c.getGender())
                .build();
    }

    /**
     * Pre-fills the onboarding contract type from the candidate's employment type.
     * The candidate's employment type uses the lifecycle vocabulary (CDI/CDD/…)
     * while the onboarding form uses PERMANENT/FIXED_TERM/INTERN/CONSULTANT, so we
     * map the known ones and leave the rest blank (the user then picks).
     */
    private String onboardingContractTypeFromCandidate(Long employmentTypeId) {
        if (employmentTypeId == null) return null;
        String code = contractTypeBridge.resolveContractTypeCode(employmentTypeId);
        if (code == null) return null;
        return switch (code) {
            case "CDI"                 -> "PERMANENT";
            case "CDD"                 -> "FIXED_TERM";
            case "STAGE", "CIVP"       -> "INTERN";
            case "PORTAGE", "FREELANCE" -> "CONSULTANT";
            default                    -> null; // DETACHEMENT / unknown → let the user choose
        };
    }

    private RegimeSummary toRegimeSummary(WorkingTimeRegime r) {
        return RegimeSummary.builder()
                .id(r.getId())
                .code(r.getCode())
                .labelFr(r.getLabelFr())
                .labelEn(r.getLabelEn())
                .hoursPerWeek(r.getHoursPerWeek())
                .daysPerWeek(r.getDaysPerWeek())
                .isFlexible(r.getIsFlexible())
                .isDefault(r.getIsDefault())
                .build();
    }

    private String buildLicenseLabel(ItProvisioning prov) {
        java.util.List<String> licenses = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(prov.getLicenseOffice365()))  licenses.add("Microsoft 365");
        if (Boolean.TRUE.equals(prov.getLicenseAutocad()))    licenses.add("AutoCAD");
        if (Boolean.TRUE.equals(prov.getLicenseRevit()))      licenses.add("Revit");
        if (Boolean.TRUE.equals(prov.getLicenseAutodesk()))   licenses.add("Autodesk");
        if (Boolean.TRUE.equals(prov.getLicenseKaspersky()))  licenses.add("Kaspersky");
        if (prov.getLicenseOther() != null && !prov.getLicenseOther().isBlank()) {
            licenses.add(prov.getLicenseOther());
        }
        return licenses.isEmpty() ? null : String.join(", ", licenses);
    }

    private String provisioningStatusLabel(ItProvisioningStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PENDING       -> "En attente";
            case IN_PROGRESS   -> "En cours";
            case EMAIL_CREATED -> "Email créé";
            case COMPLETED     -> "Complété";
        };
    }

    private SaveDraftRequest deserializeDraft(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SaveDraftRequest.class);
        } catch (JsonProcessingException ex) {
            log.warn("Could not deserialize onboarding draft JSON: {}", ex.getMessage());
            return null;
        }
    }

    private OnboardingFormResponse buildFormResponse(Candidate c,
                                                     ItProvisioning prov,
                                                     List<WorkingTimeRegime> regimes,
                                                     SaveDraftRequest draft,
                                                     OffsetDateTime draftSavedAt,
                                                     EmployeeProfile existingProfile) {
        boolean hasDraft   = draft != null;
        boolean hasProfile = existingProfile != null;

        String matricule = hasProfile ? String.format("EMP-%05d", existingProfile.getId()) : null;

        String itDeviceName = prov != null
                ? prov.getAssets().stream()
                        .filter(a -> a.getBrandModel() != null && !a.getBrandModel().isBlank())
                        .findFirst()
                        .map(ItAsset::getBrandModel)
                        .orElse(null)
                : null;

        // Priority: draft > existing profile > blank
        // Helper: return draft value, or profile value, or null
        return OnboardingFormResponse.builder()
                // Section 1 — Identity
                .candidateId(c.getId())
                .paysId(c.getPaysId())
                .firstName(c.getFirstName())
                .lastName(c.getLastName())
                .emailPersonal(c.getEmailPersonal())
                .phone(c.getPhone())
                .dateOfBirth(hasDraft ? draft.getDateOfBirth()
                           : hasProfile ? existingProfile.getDateOfBirth() : c.getDateOfBirth())
                .nationality(c.getNationality() != null ? c.getNationality().getLabelFr() : null)
                .nationalId(hasDraft ? draft.getNationalId()
                          : hasProfile ? existingProfile.getNationalId() : c.getNationalId())
                .ms365Email(prov != null ? prov.getMs365Email() : null)
                // The vacancy behind this hire — read straight off the candidate, so it holds
                // for the whole wizard without another lookup per step.
                .recruitmentDemandId(c.getRecruitmentDemandId())
                .recruitmentDemandJobTitle(resolveDemandTitle(c.getRecruitmentDemandId()))
                // Section 2 — Employment
                .appliedPosition(c.getAppliedPosition())
                .appliedGrade(c.getAppliedGrade() != null ? c.getAppliedGrade().getLabelFr() : null)
                .appliedDiscipline(c.getAppliedDiscipline() != null ? c.getAppliedDiscipline().getLabelFr() : null)
                .department(c.getDepartment() != null ? c.getDepartment().getLabelFr() : null)
                .contractType(hasDraft ? draft.getContractType()
                            : hasProfile ? existingProfile.getContractType()
                            : onboardingContractTypeFromCandidate(c.getEmploymentTypeId()))
                .expectedStartDate(c.getExpectedStartDate())
                .hireDate(hasProfile ? existingProfile.getHireDate() : null)
                .contractEndDate(hasProfile ? existingProfile.getContractEndDate() : null)
                .probationEndDate(hasProfile ? existingProfile.getProbationEndDate() : null)
                .isOnProbation(hasProfile ? existingProfile.getIsOnProbation() : false)
                // Dimension FK IDs — profile first, then candidate fallback
                .gradeId(hasProfile && existingProfile.getGrade() != null
                        ? existingProfile.getGrade().getId()
                        : c.getAppliedGrade() != null ? c.getAppliedGrade().getId() : null)
                .disciplineId(hasProfile && existingProfile.getDiscipline() != null
                        ? existingProfile.getDiscipline().getId()
                        : c.getAppliedDiscipline() != null ? c.getAppliedDiscipline().getId() : null)
                .nogLevelId(hasProfile && existingProfile.getNogLevel() != null
                        ? existingProfile.getNogLevel().getId() : null)
                .departmentId(hasProfile && existingProfile.getDepartment() != null
                        ? existingProfile.getDepartment().getId()
                        : c.getDepartment() != null ? c.getDepartment().getId() : null)
                // Section 2c — Contrat
                .recruitment(buildRecruitmentSummary(c))
                // Préavis: draft > profile's contract > the accepted offer > grade default.
                // The offer/grade fallbacks make the field arrive PREFILLED so RH confirms a
                // figure instead of typing one from memory — which is the whole point of the
                // step. It stays editable; only after completion is it fixed.
                .noticePeriodDays(hasDraft && draft.getNoticePeriodDays() != null
                                ? draft.getNoticePeriodDays()
                                : resolvePrefilledNotice(c, existingProfile))
                .agreedNetSalary(hasDraft && draft.getAgreedNetSalary() != null
                               ? draft.getAgreedNetSalary()
                               : hasProfile && existingProfile.getSalaireNetRh() != null
                                 ? existingProfile.getSalaireNetRh()
                                 : resolveOfferSalary(c))
                .contractDocumentUrl(hasDraft ? draft.getContractDocumentUrl() : null)
                .contractDocumentName(hasDraft ? draft.getContractDocumentName() : null)
                // Section 3 — Regime
                .availableRegimes(regimes.stream().map(this::toRegimeSummary).collect(Collectors.toList()))
                .selectedRegimeId(hasDraft ? draft.getRegimeTemplateId()
                                : hasProfile ? existingProfile.getRegimeTemplateId() : null)
                // Section 4 — Personal & Social
                // Gender: draft > existing profile > candidate (gender is now captured on
                // the candidate itself, so onboarding pre-fills instead of asking afresh).
                .gender(hasDraft ? draft.getGender()
                       : hasProfile ? existingProfile.getGender()
                       : GenderNormalizer.normalize(c.getGender()))
                .nationalityId(hasProfile && existingProfile.getNationality() != null
                        ? existingProfile.getNationality().getId()
                        : c.getNationality() != null ? c.getNationality().getId() : null)
                .passportNumber(hasDraft ? draft.getPassportNumber()
                              : hasProfile ? existingProfile.getPassportNumber() : null)
                .cnssNumber(hasDraft ? draft.getCnssNumber()
                          : hasProfile ? existingProfile.getCnssNumber() : null)
                .cnssAffiliationDate(hasDraft ? draft.getCnssAffiliationDate()
                                   : hasProfile ? existingProfile.getCnssAffiliationDate() : null)
                .maritalStatus(hasDraft ? draft.getMaritalStatus()
                             : hasProfile ? existingProfile.getMaritalStatus() : null)
                .numberOfChildren(hasDraft ? draft.getNumberOfChildren()
                                : hasProfile ? existingProfile.getNumberOfChildren() : null)
                .personalAddress(hasDraft ? draft.getPersonalAddress()
                               : hasProfile ? existingProfile.getPersonalAddress() : null)
                // Section 5 — Bank / RIB
                .bankId(hasProfile && existingProfile.getBank() != null
                        ? existingProfile.getBank().getId() : null)
                .bankName(hasProfile && existingProfile.getBank() != null
                        ? existingProfile.getBank().getLabelFr()
                        : hasDraft ? draft.getBankName() : null)
                .bankAccountNumber(hasDraft ? draft.getBankAccountNumber()
                                 : hasProfile ? existingProfile.getBankAccountNumber() : null)
                .rib(hasDraft ? draft.getRib()
                   : hasProfile ? existingProfile.getRib() : null)
                .iban(hasDraft ? draft.getIban()
                    : hasProfile ? existingProfile.getIban() : null)
                .socialSecurityNumber(hasDraft ? draft.getSocialSecurityNumber()
                                    : hasProfile ? existingProfile.getSocialSecurityNumber() : null)
                .taxId(hasDraft ? draft.getTaxId()
                     : hasProfile ? existingProfile.getTaxId() : null)
                // Section 6 — Emergency contact
                .emergencyContactName(hasDraft ? draft.getEmergencyContactName()
                                    : hasProfile ? existingProfile.getEmergencyContactName() : null)
                .emergencyContactRelation(hasDraft ? draft.getEmergencyContactRelation()
                                        : hasProfile ? existingProfile.getEmergencyContactRelation() : null)
                .emergencyContactPhone(hasDraft ? draft.getEmergencyContactPhone()
                                     : hasProfile ? existingProfile.getEmergencyContactPhone() : null)
                // Section 7 — Document slots
                .requiredDocumentSlots(REQUIRED_DOCUMENT_SLOTS)
                // Section 8 — Provisioning & HR timeline
                .matricule(matricule)
                .itDeviceName(itDeviceName)
                .ms365LicenseType(prov != null ? buildLicenseLabel(prov) : null)
                .itProvisioningStatus(prov != null ? provisioningStatusLabel(prov.getStatus()) : null)
                .requestValidatedAt(c.getAcceptedAt())
                .itAccountCreatedAt(prov != null ? prov.getMs365EmailCreatedAt() : null)
                .equipmentAssignedAt(prov != null ? prov.getCompletedAt() : null)
                // Meta
                .candidateStatus(c.getStatus())
                .hasDraft(hasDraft)
                .draftSavedAt(draftSavedAt)
                .build();
    }

    // ─── Section 2c — Contrat: the signed PDF ─────────────────────────────────

    /**
     * Stages the signed contract against the candidate and returns {url, name} for the draft.
     *
     * Gated on the same candidate statuses as saving a draft, so a file cannot be attached to
     * a candidature that is closed or not yet at the onboarding stage.
     */
    public Map<String, String> stageContractDocument(Long candidateId, MultipartFile file)
            throws java.io.IOException {
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND,
                        "Candidat introuvable : id=" + candidateId));
        if (!DRAFT_ALLOWED_STATUSES.contains(candidate.getStatus())) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Le contrat ne peut être joint qu'à un candidat en cours d'onboarding.");
        }
        return documentService.stageCandidateDocument(candidateId, file);
    }

    // ─── Section 2c — Contrat: completion side effects ────────────────────────

    /**
     * Creates the employee_contracts row this completion implies, carrying the agreed préavis.
     *
     * NON-FATAL by design. `createContractFromBridge` throws when the pays × contract type has
     * no ContractTypeConfig row, and refusing to finish an otherwise-complete onboarding over
     * a missing configuration row would strand the employee mid-hire — with the candidate
     * already HIRED and the workflow already created. It warns loudly instead; the contract can
     * be created from the profile afterwards, which is the same screen that would fix the
     * configuration.
     *
     * Skipped when the profile already has a contract: a candidate can arrive here after the
     * candidates-page hire flow, and two contracts for one hire is worse than none.
     */
    private void createLifecycleContract(EmployeeProfile profile, Candidate candidate,
                                         CompleteProfileRequest dto, Long hrOfficerId) {
        if (profile.getCurrentContractId() != null) {
            log.info("Profile {} already has contract {} — onboarding will not create a second one.",
                    profile.getId(), profile.getCurrentContractId());
            return;
        }
        try {
            String contractTypeCode = contractTypeBridge.resolveContractTypeCode(
                    candidate.getEmploymentTypeId());
            if (contractTypeCode == null) {
                log.warn("No contract type resolvable for candidate {} (employmentTypeId={}) — "
                         + "no lifecycle contract created, so its préavis has nowhere to live.",
                        candidate.getId(), candidate.getEmploymentTypeId());
                return;
            }

            CreateContractRequest req = new CreateContractRequest();
            req.setEmployeeProfileId(profile.getId());
            req.setPaysId(candidate.getPaysId());
            req.setContractTypeCode(contractTypeCode);
            req.setDateDebut(dto.getHireDate());
            req.setDateFinPrevue(dto.getContractEndDate());
            // Null is fine and meaningful: doCreateContract then resolves the négociated figure
            // from the offer, then the grade default, and records which one it used.
            req.setNoticePeriodDays(dto.getNoticePeriodDays());

            var created = lifecycleService.createContractFromBridge(req, hrOfficerId);
            log.info("Onboarding created contract {} for profile {} — préavis {} j",
                    created.getId(), profile.getId(), created.getNoticePeriodDays());
        } catch (Exception ex) {
            log.warn("Could not create the lifecycle contract for profile {} during onboarding: {}"
                     + " — the profile is complete but has no contract row.",
                    profile.getId(), ex.getMessage());
        }
    }

    /** Turns the contract PDF staged against the candidate into a document on the profile. */
    private void linkContractDocument(EmployeeProfile profile, CompleteProfileRequest dto,
                                      Long hrOfficerId) {
        if (dto.getContractDocumentUrl() == null || dto.getContractDocumentUrl().isBlank()) return;
        try {
            documentService.registerStagedDocument(
                    profile.getId(), dto.getContractDocumentUrl(), dto.getContractDocumentName(),
                    "CONTRACT_SIGNED", hrOfficerId);
        } catch (Exception ex) {
            // The file is on disk either way; losing the row is recoverable by re-uploading.
            log.warn("Could not attach the signed contract to profile {}: {}",
                    profile.getId(), ex.getMessage());
        }
    }

    // ─── Section 2c — Contrat: the recruitment recap ─────────────────────────

    private static final String OFFER_SQL =
            "SELECT asked_salary, proposed_salary, salary_note, notice_period_days, " +
            "       notice_period_note, expected_hire_date, expiry_date, status, sent_at, decided_at " +
            "FROM [dbo].[job_offers] WHERE candidate_id = ?";

    private static final String COST_APPROVAL_SQL =
            "SELECT status, salaire_net_rh, salaire_net_candidat, contre_prop_salaire, " +
            "       approval_notes, submitted_at, approved_at " +
            "FROM [dbo].[candidate_cost_approvals] WHERE candidate_id = ? ORDER BY submitted_at DESC";

    private static final String INTERVIEW_NOTES_SQL =
            "SELECT ci.sequence_number, it.name AS interview_type, ci.result, " +
            "       ci.interviewer_notes, ci.scheduled_at " +
            "FROM [dbo].[candidate_interviews] ci " +
            "LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id " +
            "WHERE ci.candidate_id = ? AND ci.interviewer_notes IS NOT NULL " +
            "  AND LTRIM(RTRIM(ci.interviewer_notes)) <> '' " +
            "ORDER BY ci.sequence_number ASC";

    /**
     * Gathers what recruitment already decided, for RH to confirm rather than retype.
     *
     * Read via JdbcTemplate rather than the JPA entities on purpose: this is a read-only
     * recap assembled inside a form response, and going through JobOffer /
     * CandidateCostApproval / CandidateInterview would drag three lazy graphs into it for
     * fields the step only displays.
     *
     * Degrades to nulls and empty lists — an incomplete recap is worth showing; a 500 on the
     * onboarding form because a candidate has no offer is not.
     */
    private OnboardingRecruitmentDto buildRecruitmentSummary(Candidate c) {
        OnboardingRecruitmentDto.OfferSummary offer = null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(OFFER_SQL, c.getId());
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                offer = OnboardingRecruitmentDto.OfferSummary.builder()
                        .askedSalary(bigDecimal(r.get("asked_salary")))
                        .proposedSalary(bigDecimal(r.get("proposed_salary")))
                        .salaryNote((String) r.get("salary_note"))
                        .noticePeriodDays(integer(r.get("notice_period_days")))
                        .noticePeriodNote((String) r.get("notice_period_note"))
                        .expectedHireDate(localDate(r.get("expected_hire_date")))
                        .expiryDate(localDate(r.get("expiry_date")))
                        .status((String) r.get("status"))
                        .sentAt(offsetDateTime(r.get("sent_at")))
                        .decidedAt(offsetDateTime(r.get("decided_at")))
                        .build();
            }
        } catch (Exception ex) {
            log.debug("Could not read the offer for candidate {}: {}", c.getId(), ex.getMessage());
        }

        List<OnboardingRecruitmentDto.CostApprovalSummary> approvals = List.of();
        try {
            approvals = jdbc.queryForList(COST_APPROVAL_SQL, c.getId()).stream()
                    .map(r -> OnboardingRecruitmentDto.CostApprovalSummary.builder()
                            .status((String) r.get("status"))
                            .salaireNetRh(bigDecimal(r.get("salaire_net_rh")))
                            .salaireNetCandidat(bigDecimal(r.get("salaire_net_candidat")))
                            .contrePropSalaire(bigDecimal(r.get("contre_prop_salaire")))
                            .approvalNotes((String) r.get("approval_notes"))
                            .submittedAt(offsetDateTime(r.get("submitted_at")))
                            .approvedAt(offsetDateTime(r.get("approved_at")))
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.debug("Could not read cost approvals for candidate {}: {}", c.getId(), ex.getMessage());
        }

        List<OnboardingRecruitmentDto.InterviewNote> notes = List.of();
        try {
            notes = jdbc.queryForList(INTERVIEW_NOTES_SQL, c.getId()).stream()
                    .map(r -> OnboardingRecruitmentDto.InterviewNote.builder()
                            .sequenceNumber(integer(r.get("sequence_number")))
                            .interviewType((String) r.get("interview_type"))
                            .result((String) r.get("result"))
                            .interviewerNotes((String) r.get("interviewer_notes"))
                            .scheduledAt(offsetDateTime(r.get("scheduled_at")))
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.debug("Could not read interview notes for candidate {}: {}", c.getId(), ex.getMessage());
        }

        Integer gradeDefault = null;
        try {
            gradeDefault = c.getAppliedGrade() != null
                    ? c.getAppliedGrade().getNoticePeriodDays() : null;
        } catch (Exception ex) {
            log.debug("Could not read the grade préavis default for candidate {}: {}",
                    c.getId(), ex.getMessage());
        }

        return OnboardingRecruitmentDto.builder()
                .offer(offer)
                .candidateDeclaredNetSalary(c.getSalaireNetCandidat())
                .hrAssessedNetSalary(c.getSalaireNetRh())
                .gradeNoticePeriodDays(gradeDefault)
                .costApprovals(approvals)
                .interviewNotes(notes)
                .build();
    }

    /**
     * The préavis the Contrat step opens on: the figure already frozen on the employee's
     * contract if one exists (re-running an incomplete onboarding must not silently propose a
     * different number), else the negotiated offer, else the grade default.
     */
    private Integer resolvePrefilledNotice(Candidate c, EmployeeProfile profile) {
        if (profile != null && profile.getCurrentContractId() != null) {
            Integer fromContract = queryInteger(
                    "SELECT notice_period_days FROM [dbo].[employee_contracts] WHERE id = ?",
                    profile.getCurrentContractId());
            if (fromContract != null) return fromContract;
        }
        Integer fromOffer = queryInteger(
                "SELECT notice_period_days FROM [dbo].[job_offers] WHERE candidate_id = ?", c.getId());
        if (fromOffer != null) return fromOffer;
        try {
            return c.getAppliedGrade() != null ? c.getAppliedGrade().getNoticePeriodDays() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** The salary actually offered — the figure that used to have to be retyped. */
    private BigDecimal resolveOfferSalary(Candidate c) {
        try {
            List<BigDecimal> rows = jdbc.queryForList(
                    "SELECT proposed_salary FROM [dbo].[job_offers] WHERE candidate_id = ?",
                    BigDecimal.class, c.getId());
            if (!rows.isEmpty() && rows.get(0) != null) return rows.get(0);
        } catch (Exception ex) {
            log.debug("Could not read the offer salary for candidate {}: {}", c.getId(), ex.getMessage());
        }
        // Falls back to HR's assessment, then the candidate's own declaration.
        return c.getSalaireNetRh() != null ? c.getSalaireNetRh() : c.getSalaireNetCandidat();
    }

    /**
     * The vacancy's display title. Read via JdbcTemplate rather than the repository so this
     * stays a one-column lookup — the wizard only shows the name.
     */
    private String resolveDemandTitle(Long demandId) {
        if (demandId == null) return null;
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT COALESCE(NULLIF(LTRIM(RTRIM(job_exact_title)), ''), job_title) "
                  + "FROM [dbo].[recruitment_demands] WHERE id = ?",
                    String.class, demandId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.debug("Could not read the recruitment demand title for {}: {}", demandId, ex.getMessage());
            return null;
        }
    }

    private Integer queryInteger(String sql, Object arg) {
        try {
            List<Integer> rows = jdbc.queryForList(sql, Integer.class, arg);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            return null;
        }
    }

    // ─── Null-safe JDBC coercions for the recap ──────────────────────────────

    private static BigDecimal bigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private static Integer integer(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static LocalDate localDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static OffsetDateTime offsetDateTime(Object v) {
        if (v == null) return null;
        if (v instanceof OffsetDateTime o) return o;
        if (v instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
