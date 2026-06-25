package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.ItAsset;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.onboarding.CompleteProfileRequest;
import com.daf360.rh.dto.onboarding.CompletionResult;
import com.daf360.rh.dto.onboarding.OnboardingFormResponse;
import com.daf360.rh.dto.onboarding.OnboardingKpiDto;
import com.daf360.rh.dto.onboarding.OnboardingListItem;
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
        // Regime
        profile.setRegimeTemplateId(dto.getRegimeTemplateId());
        profile.setRegimeStartDate(dto.getRegimeStartDate());
        // Personal & Social
        profile.setCnssNumber(dto.getCnssNumber());
        profile.setCnssAffiliationDate(dto.getCnssAffiliationDate());
        profile.setMaritalStatus(dto.getMaritalStatus());
        profile.setNumberOfChildren(dto.getNumberOfChildren());
        profile.setDateOfBirth(dto.getDateOfBirth());
        profile.setGender(dto.getGender());
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

        // STEP 3 — Delete onboarding draft
        jdbc.update("DELETE FROM [dbo].[onboarding_drafts] WHERE candidate_id = ?", candidateId);

        // STEP 4 — Create workflow instance
        Long workflowId = workflowInstanceService.createOnboardingInstance(
                saved.getId(), hrOfficerId, candidate.getPaysId(), dto.getHireDate());

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
                .directUserId(prov.getUserId())
                // directUserId bypasses role routing — sends directly to the new employee
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

        // STEP 8 — Audit log
        auditService.log(
                hrOfficerId.toString(),
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
                .build();
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
                // Section 2 — Employment
                .appliedPosition(c.getAppliedPosition())
                .appliedGrade(c.getAppliedGrade() != null ? c.getAppliedGrade().getLabelFr() : null)
                .appliedDiscipline(c.getAppliedDiscipline() != null ? c.getAppliedDiscipline().getLabelFr() : null)
                .department(c.getDepartment() != null ? c.getDepartment().getLabelFr() : null)
                .contractType(hasDraft ? draft.getContractType()
                            : hasProfile ? existingProfile.getContractType() : null)
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
                // Section 3 — Regime
                .availableRegimes(regimes.stream().map(this::toRegimeSummary).collect(Collectors.toList()))
                .selectedRegimeId(hasDraft ? draft.getRegimeTemplateId()
                                : hasProfile ? existingProfile.getRegimeTemplateId() : null)
                // Section 4 — Personal & Social
                .gender(hasProfile ? existingProfile.getGender() : null)
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
}
