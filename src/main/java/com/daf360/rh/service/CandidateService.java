package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.dto.candidate.*;
import com.daf360.rh.dto.lifecycle.CreateContractRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.lifecycle.ContractTypeBridge;
import com.daf360.rh.lifecycle.EmployeeLifecycleService;
import com.daf360.rh.mapper.CandidateMapper;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.RecruitmentDemandRepository;
import com.daf360.rh.repository.DisciplineRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.GradeRepository;
import com.daf360.rh.repository.HrDepartmentRepository;
import com.daf360.rh.repository.ItAssetRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.repository.NationalityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;



import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CandidateService {

    private static final Set<String> ALLOWED_CV_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final long MAX_CV_SIZE = 10 * 1024 * 1024L; // 10 MB

    private final CandidateRepository          candidateRepo;
    private final CandidateInterviewRepository interviewRepo;
    private final RecruitmentDemandRepository  recruitmentDemandRepo;
    private final ItProvisioningRepository   itProvRepo;
    private final ItAssetRepository          itAssetRepo;
    private final EmployeeProfileRepository  profileRepo;
    private final CandidateMapper            mapper;
    private final AuditService               auditService;
    private final AppProperties              appProperties;
    private final JdbcTemplate               jdbc;
    private final EmployeeLifecycleService   lifecycleService;
    private final ContractTypeBridge         contractTypeBridge;
    private final com.daf360.rh.lists.ContractTypeRefs contractTypeRefs;
    private final com.daf360.rh.security.TenantService tenantService;

    // ── Dimension repos for FK resolution ────────────────────────────────────
    private final NationalityRepository           nationalityRepo;
    private final GradeRepository                 gradeRepo;
    private final DisciplineRepository            disciplineRepo;
    private final HrDepartmentRepository          departmentRepo;
    private final ConfigurableListValueRepository listValueRepo;
    /** Only to resolve the CONTRACT_TYPE list id — see applyEmploymentType. */
    private final com.daf360.rh.lists.ConfigurableListTypeRepository listTypeRepo;
    /** Equipment ledger (V76) — seeded here too, see hireCandidate. */
    private final ItAssetAssignmentService        assetAssignmentService;
    /** Entity timezone — reads the recruitment list's date filter as local calendar days. */
    private final PaysTimezoneService             paysTimezoneService;
    /** V132: who may read the record (interviewers without RH codes included). */
    private final InterviewAccess                 interviewAccess;

    // ── Public API ────────────────────────────────────────────────────────────

    public CandidateResponse createCandidate(CreateCandidateRequest request, Long actorUserId) {
        if (candidateRepo.existsByEmailPersonal(request.getEmailPersonal())) {
            throw new AppException(ErrorCode.CANDIDATE_EMAIL_DUPLICATE);
        }

        Candidate candidate = mapper.toEntity(request);
        // Prénom with leading capitals, NOM in capitals — the same rule as the profile page.
        String firstName = com.daf360.rh.common.PersonNameFormatter.formatFirstName(request.getFirstName());
        String lastName  = com.daf360.rh.common.PersonNameFormatter.formatLastName(request.getLastName());
        if (firstName != null) candidate.setFirstName(firstName);
        if (lastName  != null) candidate.setLastName(lastName);
        candidate.setGender(com.daf360.rh.common.GenderNormalizer.normalize(request.getGender()));
        applyDimensionFks(candidate,
                request.getNationalityId(), request.getAppliedGradeId(),
                request.getAppliedDisciplineId(), request.getDepartmentId());
        // After the FKs: the pays check inside needs the candidate's own paysId, which
        // toEntity has already copied from the request.
        applyRecruitmentDemand(candidate, request.getRecruitmentDemandId());
        candidate.setCreatedBy(actorUserId);
        candidate.setCreatedAt(OffsetDateTime.now());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        auditService.log(actorUserId.toString(), "CREATE", "CANDIDATE", candidate.getId(),
                null, "status=PENDING");

        return toFullResponse(candidate);
    }

    public CandidateResponse updateCandidate(Long id, UpdateCandidateRequest request, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (request.getEmailPersonal() != null
                && !request.getEmailPersonal().equalsIgnoreCase(candidate.getEmailPersonal())
                && candidateRepo.existsByEmailPersonal(request.getEmailPersonal())) {
            throw new AppException(ErrorCode.CANDIDATE_EMAIL_DUPLICATE);
        }

        String before = "status=" + candidate.getStatus();
        mapper.updateEntity(candidate, request);
        if (request.getGender() != null) {
            candidate.setGender(com.daf360.rh.common.GenderNormalizer.normalize(request.getGender()));
        }
        applyDimensionFks(candidate,
                request.getNationalityId(), request.getAppliedGradeId(),
                request.getAppliedDisciplineId(), request.getDepartmentId());
        applyEmploymentType(candidate, request.getEmploymentTypeId());
        // Absent → left as it was; present (including null) → applied. That is what lets a
        // spontaneous application be attached to a vacancy later, or detached.
        if (request.isRecruitmentDemandProvided()) {
            applyRecruitmentDemand(candidate, request.getRecruitmentDemandId());
        }
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        auditService.log(actorUserId.toString(), "UPDATE", "CANDIDATE", candidate.getId(),
                before, "email=" + candidate.getEmailPersonal()
                      + "; recruitmentDemandId=" + candidate.getRecruitmentDemandId()
                      + "; employmentTypeId=" + candidate.getEmploymentTypeId());

        return toFullResponse(candidate);
    }

    public CandidateResponse acceptCandidate(Long id, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Seuls les candidats PENDING peuvent être acceptés");
        }

        // Step gate: the candidature only moves on once the interview step is done — at
        // least one PASS and no FAIL, the same rule the offer applies. Accepting also opens
        // the IT provisioning task, so letting it through earlier would start IT work for
        // a candidate who has not been interviewed.
        List<CandidateInterview> interviews = interviewRepo.findByCandidateIdOrderBySequenceNumber(id);
        boolean anyPass = interviews.stream().anyMatch(i -> i.getResult() == InterviewResult.PASS);
        boolean anyFail = interviews.stream().anyMatch(i -> i.getResult() == InterviewResult.FAIL);
        if (!anyPass || anyFail) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La candidature ne peut être validée qu'après au moins un entretien réussi et aucun échec.");
        }
        // V132: and nothing left open — an interview still planned, or held but whose PVs RH
        // has not validated, could still turn into a FAIL.
        boolean open = interviews.stream().anyMatch(i ->
                i.getStatus() == com.daf360.rh.domain.enums.InterviewStatus.PLANNED
                || (i.getStatus() == com.daf360.rh.domain.enums.InterviewStatus.DONE && i.getResult() == null));
        if (open) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Des entretiens sont encore planifiés ou attendent la validation de leur PV : "
                    + "terminez-les ou annulez-les avant de valider la candidature.");
        }

        candidate.setStatus(CandidateStatus.ACCEPTED);
        candidate.setAcceptedBy(actorUserId);
        candidate.setAcceptedAt(OffsetDateTime.now());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        // No IT provisioning here any more: the candidature only moves on to the offer. The IT
        // file and onboarding open together once the salary is agreed — see
        // HiringHandoffService, called by OfferService — so IT never prepares equipment for
        // someone still negotiating. The CANDIDATE_ACCEPTED event moved there with it.

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "ACCEPT", "CANDIDATE", candidate.getId(),
                "status=PENDING", "status=ACCEPTED");

        return toFullResponse(candidate);
    }

    public CandidateResponse rejectCandidate(Long id, RejectCandidateRequest request, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Seuls les candidats PENDING peuvent être rejetés");
        }

        candidate.setStatus(CandidateStatus.REJECTED);
        candidate.setRejectionReason(request.getRejectionReason());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "REJECT", "CANDIDATE", candidate.getId(),
                "status=PENDING", "status=REJECTED; reason=" + request.getRejectionReason());

        return toFullResponse(candidate);
    }

    private static final Set<CandidateStatus> HIREABLE_STATUSES = Set.of(
            CandidateStatus.ACCEPTED, CandidateStatus.EMAIL_RECEIVED, CandidateStatus.HR_IN_PROGRESS);

    private static final Set<String> NEEDS_END_DATE = Set.of("CDD", "CIVP", "STAGE", "DETACHEMENT");

    public HireCandidateResponse hireCandidate(Long id, HireCandidateRequest req, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (!HIREABLE_STATUSES.contains(candidate.getStatus())) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Le candidat doit être en statut ACCEPTED, EMAIL_RECEIVED ou HR_IN_PROGRESS pour être recruté.");
        }

        ItProvisioning prov = itProvRepo.findByCandidateId(id)
                .orElseThrow(() -> new AppException(ErrorCode.IT_PROVISIONING_NOT_FOUND,
                        "Le provisioning IT doit être complété avant de recruter."));
        if (prov.getUserId() == null) {
            throw new AppException(ErrorCode.ONBOARDING_USER_NOT_CREATED,
                    "Le compte utilisateur n'a pas encore été créé par l'équipe IT.");
        }

        EmployeeProfile profile = profileRepo.findByUserId(prov.getUserId())
                .orElseGet(() -> EmployeeProfile.builder()
                        .userId(prov.getUserId())
                        .paysId(candidate.getPaysId())
                        .candidateId(id)
                        .hireDate(req.getHireDate())
                        .lifecycleStatus(LifecycleStatus.ACTIVE)
                        .onboardingCompleted(false)
                        .deleted(false)
                        .createdAt(OffsetDateTime.now())
                        .updatedAt(OffsetDateTime.now())
                        .build());
        profile.setHireDate(req.getHireDate());
        profile.setUpdatedAt(OffsetDateTime.now());
        profile = profileRepo.save(profile);

        // The contract stores the CONTRACT_TYPE list id; the rules follow its nature (CDI, CDD…).
        String contractTypeRef = req.getContractTypeCode() != null && !req.getContractTypeCode().isBlank()
                ? req.getContractTypeCode()
                : contractTypeBridge.resolveContractTypeRef(candidate.getEmploymentTypeId());
        String contractTypeCode = contractTypeRefs.natureOf(contractTypeRef, candidate.getPaysId());

        if (NEEDS_END_DATE.contains(contractTypeCode) && req.getDateFinPrevue() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La date de fin est obligatoire pour le type de contrat : " + contractTypeCode);
        }

        CreateContractRequest contractReq = new CreateContractRequest();
        contractReq.setEmployeeProfileId(profile.getId());
        contractReq.setPaysId(candidate.getPaysId());
        contractReq.setContractTypeCode(contractTypeRef);
        contractReq.setDateDebut(req.getHireDate());
        contractReq.setDateFinPrevue(req.getDateFinPrevue());
        contractReq.setManagerProfile(req.isManagerProfile());
        // No noticePeriodDays: leaving it null lets doCreateContract resolve the négociated
        // figure from this candidate's own offer (stamped NEGOTIATED), then the grade default.
        // Setting it here would only be able to repeat the same lookup and would stamp the
        // result MANUAL, losing where it came from.

        var created = lifecycleService.createContractFromBridge(contractReq, actorUserId);

        candidate.setStatus(CandidateStatus.HIRED);
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepo.save(candidate);

        /*
         * Open the IT equipment ledger for the new employee (V76).
         *
         * Required HERE and not only in ItProvisioningService / OnboardingService: this is the
         * OTHER hire path. IT normally completes provisioning before any profile exists, so the
         * hook there finds nothing to attach to, and this flow never touches the onboarding
         * wizard. Without this call the equipment list stayed in `it_assets` and the profile's
         * "Matériel IT" tab looked empty until someone pressed "Synchroniser".
         *
         * Idempotent on (provisioning, asset type), so a profile that later goes through the
         * wizard gains no duplicates. Non-fatal: the hire itself must not fail over a projection.
         */
        try {
            assetAssignmentService.seedFromProvisioning(prov.getId(), actorUserId);
        } catch (Exception ex) {
            log.warn("IT asset ledger seeding failed for candidateId={}: {}", id, ex.getMessage());
        }

        auditService.log(actorUserId.toString(), "HIRE_CANDIDATE", "CANDIDATE", id,
                "status=" + candidate.getStatus(), "status=HIRED; contractType=" + contractTypeCode);

        return HireCandidateResponse.builder()
                .candidateId(id)
                .employeeProfileId(profile.getId())
                .contractId(created.getId())
                .contractTypeCode(contractTypeCode)
                .userId(prov.getUserId())
                .message("Candidat recruté. Contrat " + contractTypeCode + " créé avec succès.")
                .build();
    }

    @Transactional(readOnly = true)
    public Page<CandidateListItem> listCandidates(CandidateStatus status, String stage,
                                                   Long paysId, String search,
                                                   CandidateListFilter filter, Pageable pageable) {
        // Enforce tenant isolation: non-admin users can only see their own entity's candidates.
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;

        // Calendar days as the entity sees them; UTC when the entity has no zone (or the
        // admin views every entity). `createdTo` is inclusive, so the bound is the next day.
        ZoneId zone = resolvedPaysId != null ? paysTimezoneService.zoneFor(resolvedPaysId) : null;
        if (zone == null) zone = ZoneOffset.UTC;
        OffsetDateTime createdFrom = filter.createdFrom() == null ? null
                : filter.createdFrom().atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime createdTo = filter.createdTo() == null ? null
                : filter.createdTo().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Long demandId = filter.spontaneous() ? null : filter.demandId();

        // A status is the narrower filter and wins; a stage alone is a set of statuses. The
        // stage query has no ORDER BY of its own (it overrode the list's column sort), so an
        // unsorted request gets the usual newest-first.
        boolean byStage = status == null && stage != null && !stage.isBlank();
        Pageable stagePage = byStage && pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        Page<Candidate> page = byStage
                ? candidateRepo.searchByStatusesAndSearch(stageToStatuses(stage), resolvedPaysId, search,
                        filter.departmentId(), demandId, filter.spontaneous(), createdFrom, createdTo, stagePage)
                : candidateRepo.searchPaged(status, resolvedPaysId, search,
                        filter.departmentId(), demandId, filter.spontaneous(), createdFrom, createdTo, pageable);

        Map<Long, String> contractLabels = resolveEmploymentTypeLabels(page.getContent());
        Map<Long, CandidateInterview> nextInterviews = resolveNextInterviews(page.getContent());
        Map<Long, String> demandTitles = resolveDemandTitles(page.getContent());

        return page.map(candidate -> {
            CandidateListItem item = mapper.toListItem(candidate);
            if (candidate.getEmploymentTypeId() != null) {
                item.setContractType(contractLabels.get(candidate.getEmploymentTypeId()));
            }
            item.setRecruitmentDemandId(candidate.getRecruitmentDemandId());
            if (candidate.getRecruitmentDemandId() != null) {
                item.setRecruitmentDemandJobTitle(demandTitles.get(candidate.getRecruitmentDemandId()));
            }
            CandidateInterview next = nextInterviews.get(candidate.getId());
            if (next != null) {
                item.setNextInterviewAt(next.getScheduledAt());
                item.setNextInterviewLocation(next.getLocation());
            }
            return item;
        });
    }

    /**
     * Batch-loads each candidate's next PLANNED interview (earliest scheduledAt).
     * One query for the whole page — avoids an N+1 per card on the kanban.
     */
    private Map<Long, CandidateInterview> resolveNextInterviews(List<Candidate> candidates) {
        Set<Long> ids = candidates.stream()
                .map(Candidate::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, CandidateInterview> earliest = new java.util.HashMap<>();
        // Query returns rows ordered by scheduledAt ASC → first seen per candidate is the earliest.
        for (CandidateInterview ci : interviewRepo.findPlannedByCandidateIds(ids)) {
            earliest.putIfAbsent(ci.getCandidateId(), ci);
        }
        return earliest;
    }

    /** Batch-loads employment-type labels for a page of candidates (avoids N+1). */
    /**
     * Batch-loads the vacancy title for a page of candidates.
     *
     * One query for the page rather than {@code demandTitle()} per row: the list is paged at
     * 20+ and several candidatures usually answer the SAME demand, so per-row lookups would be
     * both N+1 and largely duplicated.
     */
    private Map<Long, String> resolveDemandTitles(List<Candidate> candidates) {
        Set<Long> ids = candidates.stream()
                .map(Candidate::getRecruitmentDemandId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> titles = new java.util.HashMap<>();
        recruitmentDemandRepo.findAllById(ids).forEach(d ->
                titles.put(d.getId(),
                        d.getJobExactTitle() != null && !d.getJobExactTitle().isBlank()
                                ? d.getJobExactTitle() : d.getJobTitle()));
        return titles;
    }

    private Map<Long, String> resolveEmploymentTypeLabels(List<Candidate> candidates) {
        Set<Long> typeIds = candidates.stream()
                .map(Candidate::getEmploymentTypeId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (typeIds.isEmpty()) return Map.of();
        Map<Long, String> labels = new java.util.HashMap<>();
        listValueRepo.findAllById(typeIds).forEach(v ->
                labels.put(v.getId(), v.getLabelFr() != null ? v.getLabelFr() : v.getLabelEn()));
        return labels;
    }

    /**
     * KPI tiles for the /candidates dashboard: total (+ 30-day growth), average
     * recruitment delay (+ 90-day trend) and urgent open positions. All figures
     * are tenant-scoped when the caller resolves to a specific pays.
     */
    @Transactional(readOnly = true)
    public CandidateDashboardStats getDashboardStats() {
        Long paysId = tenantService.getEffectivePaysId();
        String scope    = paysId != null ? " AND pays_id = ?" : "";
        Object[] args   = paysId != null ? new Object[]{ paysId } : new Object[]{};

        long total  = firstLong("SELECT COUNT(*) FROM [dbo].[candidates] WHERE 1=1" + scope, args);

        long curr30 = firstLong(
                "SELECT COUNT(*) FROM [dbo].[candidates] " +
                "WHERE created_at >= DATEADD(DAY,-30,SYSDATETIMEOFFSET())" + scope, args);
        long prev30 = firstLong(
                "SELECT COUNT(*) FROM [dbo].[candidates] " +
                "WHERE created_at >= DATEADD(DAY,-60,SYSDATETIMEOFFSET()) " +
                "  AND created_at <  DATEADD(DAY,-30,SYSDATETIMEOFFSET())" + scope, args);
        Double monthGrowthPct = prev30 > 0 ? (curr30 - prev30) * 100.0 / prev30
                                           : (curr30 > 0 ? 100.0 : null);

        Double avgAll  = firstDouble(
                "SELECT AVG(CAST(DATEDIFF(DAY, created_at, accepted_at) AS FLOAT)) " +
                "FROM [dbo].[candidates] WHERE accepted_at IS NOT NULL" + scope, args);
        Double avgCurr = firstDouble(
                "SELECT AVG(CAST(DATEDIFF(DAY, created_at, accepted_at) AS FLOAT)) " +
                "FROM [dbo].[candidates] WHERE accepted_at IS NOT NULL " +
                "  AND accepted_at >= DATEADD(DAY,-90,SYSDATETIMEOFFSET())" + scope, args);
        Double avgPrev = firstDouble(
                "SELECT AVG(CAST(DATEDIFF(DAY, created_at, accepted_at) AS FLOAT)) " +
                "FROM [dbo].[candidates] WHERE accepted_at IS NOT NULL " +
                "  AND accepted_at >= DATEADD(DAY,-180,SYSDATETIMEOFFSET()) " +
                "  AND accepted_at <  DATEADD(DAY,-90,SYSDATETIMEOFFSET())" + scope, args);
        Double avgDelta = (avgCurr != null && avgPrev != null) ? avgCurr - avgPrev : null;

        long urgent = paysId != null
                ? recruitmentDemandRepo.countPendingByPays(paysId)
                : firstLong("SELECT COUNT(*) FROM [dbo].[recruitment_demands] WHERE statut = 'EN_ATTENTE'", new Object[]{});

        // ── Funnel-health KPIs ──────────────────────────────────────────────────
        long active = firstLong(
                "SELECT COUNT(*) FROM [dbo].[candidates] WHERE status IN " +
                "('PENDING','ACCEPTED','OFFER_SENT','IT_IN_PROGRESS','EMAIL_RECEIVED','HR_IN_PROGRESS')" + scope, args);
        long hiredTotal = firstLong(
                "SELECT COUNT(*) FROM [dbo].[candidates] WHERE status = 'HIRED'" + scope, args);

        // Offer acceptance rate — only if the job_offers table exists (V41 applied);
        // checked via sys.tables so a missing table can't poison the transaction.
        Double offerAcceptanceRate = null;
        if (firstLong("SELECT COUNT(*) FROM sys.tables WHERE name = 'job_offers'", new Object[]{}) > 0) {
            String offerScope = paysId != null ? " AND c.pays_id = ?" : "";
            // One row PER CANDIDATE, not per offer row.
            //
            // Since V98 job_offers holds a row per negotiation round, and the old query
            // counted them all: a candidate renegotiated three times before accepting
            // contributed three decisions instead of one, so the rate measured how often we
            // renegotiate rather than how often offers are accepted. The inner OUTER APPLY
            // picks each candidate's decided round — accepted first, else the last refusal.
            offerAcceptanceRate = firstDouble(
                    "SELECT CASE WHEN COUNT(d.status) = 0 THEN NULL " +
                    "  ELSE 100.0 * SUM(CASE WHEN d.status = 'ACCEPTED' THEN 1 ELSE 0 END) " +
                    "       / COUNT(d.status) END " +
                    "FROM [dbo].[candidates] c " +
                    "CROSS APPLY ( " +
                    "    SELECT TOP 1 jo.status " +
                    "    FROM [dbo].[job_offers] jo " +
                    "    WHERE jo.candidate_id = c.id AND jo.status IN ('ACCEPTED','REJECTED') " +
                    "    ORDER BY CASE WHEN jo.status = 'ACCEPTED' THEN 0 ELSE 1 END, jo.id DESC " +
                    ") d " +
                    "WHERE 1=1" + offerScope, args);
        }

        return CandidateDashboardStats.builder()
                .totalCandidates(total)
                .monthGrowthPct(monthGrowthPct)
                .avgRecruitmentDays(avgAll)
                .avgRecruitmentDaysDelta(avgDelta)
                .urgentPositions(urgent)
                .activeCandidates(active)
                .hiredTotal(hiredTotal)
                .offerAcceptanceRate(offerAcceptanceRate)
                .build();
    }

    private long firstLong(String sql, Object[] args) {
        try {
            Long v = jdbc.queryForObject(sql, Long.class, args);
            return v != null ? v : 0L;
        } catch (Exception ex) {
            log.warn("Dashboard stat query failed: {}", ex.getMessage());
            return 0L;
        }
    }

    private Double firstDouble(String sql, Object[] args) {
        try {
            return jdbc.queryForObject(sql, Double.class, args);
        } catch (Exception ex) {
            return null;
        }
    }

    private static final List<CandidateStatus> CANDIDATE_WORKFLOW_STATUSES = List.of(
            CandidateStatus.PENDING, CandidateStatus.ACCEPTED, CandidateStatus.OFFER_SENT,
            CandidateStatus.REJECTED, CandidateStatus.ARCHIVED);

    private static List<CandidateStatus> stageToStatuses(String stage) {
        return switch (stage.toUpperCase()) {
            case "CANDIDATURE"          -> List.of(CandidateStatus.PENDING);
            case "SCREENING_RH"         -> List.of(CandidateStatus.ACCEPTED, CandidateStatus.HR_IN_PROGRESS);
            case "ENTRETIEN_TECHNIQUE"  -> List.of(CandidateStatus.IT_IN_PROGRESS);
            case "OFFRE_ENVOYEE"        -> List.of(CandidateStatus.EMAIL_RECEIVED);
            case "RECRUTE"              -> List.of(CandidateStatus.HIRED);
            case "REJETE"               -> List.of(CandidateStatus.REJECTED, CandidateStatus.ARCHIVED);
            // The candidates home page (/rh/candidates): only files still in the candidate /
            // negotiation workflow. Once the offer is agreed the candidate is hired in all but
            // paperwork and lives in onboarding (IT provisioning included) — never here again.
            case "CANDIDATE_WORKFLOW"   -> CANDIDATE_WORKFLOW_STATUSES;
            // « Pipeline RH » (/rh/recrutement): the whole lifecycle — in recruitment, in
            // onboarding, hired, closed. Every status, newest first like the other stages.
            case "LIFECYCLE"            -> List.of(CandidateStatus.values());
            default                    -> List.of(CandidateStatus.values());
        };
    }

    @Transactional(readOnly = true)
    public CandidateResponse getCandidate(Long id) {
        return toFullResponse(findOrThrow(id));
    }

    /**
     * GET /candidates/{id} with the V132 rule: full record for HR_ONBOARDING / IT_PROVISIONING,
     * a reduced one for an interviewer who holds neither — what they need to prepare and judge
     * an interview, without the budget, the identity documents or the IT dossier.
     */
    @Transactional(readOnly = true)
    public CandidateResponse getCandidateForViewer(Long id) {
        interviewAccess.requireCandidateRead(id);
        CandidateResponse response = toFullResponse(findOrThrow(id));
        if (!interviewAccess.hasFullRecordAccess()) {
            response.setSalaireNetRh(null);
            response.setSalaireNetCandidat(null);
            response.setNationalId(null);
            response.setPassportNumber(null);
            response.setItProvisioning(null);
            response.setCvPath(null);
            response.setInterviewerView(true);
        }
        return response;
    }

    @Transactional(readOnly = true)
    public com.daf360.rh.dto.candidate.CandidateAccessDto accessOf(Long id) {
        findOrThrow(id);
        return new com.daf360.rh.dto.candidate.CandidateAccessDto(
                interviewAccess.hasFullRecordAccess(), interviewAccess.isInterviewerOf(id));
    }

    @Transactional(readOnly = true)
    public Resource downloadCvForViewer(Long id) {
        interviewAccess.requireCvRead(id);
        return downloadCv(id);
    }

    // ── CV upload / download ──────────────────────────────────────────────────

    public CandidateResponse uploadCv(Long id, MultipartFile file, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (file.isEmpty()) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED, "Le fichier est vide");
        }
        if (file.getSize() > MAX_CV_SIZE) {
            throw new AppException(ErrorCode.DOCUMENT_SIZE_EXCEEDED,
                    "Fichier trop volumineux — max 10 Mo");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CV_TYPES.contains(contentType)) {
            throw new AppException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED,
                    "Format non supporté — PDF, DOC ou DOCX uniquement");
        }

        // Delete previous CV file if it exists
        if (candidate.getCvPath() != null) {
            try { Files.deleteIfExists(Paths.get(candidate.getCvPath())); }
            catch (IOException ex) { log.warn("Could not delete old CV: {}", ex.getMessage()); }
        }

        // Store new file: {storagePath}/candidates/{id}/{uuid}_{originalName}
        try {
            Path dir = Paths.get(appProperties.getStoragePath(), "candidates", id.toString());
            Files.createDirectories(dir);
            String storedName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path target = dir.resolve(storedName);
            Files.copy(file.getInputStream(), target);

            candidate.setCvPath(target.toString());
            candidate.setCvOriginalName(file.getOriginalFilename());
            candidate.setCvUploadedAt(OffsetDateTime.now());
            candidate.setUpdatedAt(OffsetDateTime.now());
            candidate = candidateRepo.save(candidate);

            auditService.log(actorUserId.toString(), "UPLOAD_CV", "CANDIDATE", candidate.getId(),
                    null, "cv=" + file.getOriginalFilename());
        } catch (IOException ex) {
            log.error("CV upload failed for candidateId={}: {}", id, ex.getMessage());
            throw new AppException(ErrorCode.INTERNAL_ERROR, "Échec du téléversement du CV");
        }

        return toFullResponse(candidate);
    }

    @Transactional(readOnly = true)
    public Resource downloadCv(Long id) {
        Candidate candidate = findOrThrow(id);
        if (candidate.getCvPath() == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "Aucun CV n'a été téléversé pour ce candidat");
        }
        try {
            Path file = Paths.get(candidate.getCvPath());
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new AppException(ErrorCode.NOT_FOUND, "Fichier CV introuvable sur le serveur");
            }
            return resource;
        } catch (Exception ex) {
            throw new AppException(ErrorCode.NOT_FOUND, "Fichier CV introuvable");
        }
    }

    // History moved to CandidateHistoryService (« Historique » tab).

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Candidate findOrThrow(Long id) {
        return candidateRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
    }

    /**
     * Resolves dimension FK IDs to entities and sets them on the candidate.
     * Only non-null IDs are applied so that PATCH semantics are preserved.
     */
    private void applyDimensionFks(Candidate candidate,
                                   Long nationalityId, Long gradeId,
                                   Long disciplineId, Long departmentId) {
        if (nationalityId != null) nationalityRepo.findById(nationalityId).ifPresent(candidate::setNationality);
        if (gradeId       != null) gradeRepo.findById(gradeId).ifPresent(candidate::setAppliedGrade);
        if (disciplineId  != null) disciplineRepo.findById(disciplineId).ifPresent(candidate::setAppliedDiscipline);
        if (departmentId  != null) departmentRepo.findById(departmentId).ifPresent(candidate::setDepartment);
    }

    /**
     * Attaches the candidature to the vacancy it answers — `candidates.recruitment_demand_id`.
     *
     * The column, the entity field and the request field all existed already, but nothing ever
     * wrote it: the mapper ignores it (it is a plain Long, not one of the FK dimension
     * associations) and the service never set it, so every candidate carried null and
     * `recruitmentDemandJobTitle` was permanently empty.
     *
     * Validated rather than trusted: only an APPROVED demand of the candidate's own entity can
     * be attached. An unapproved demand is not yet a vacancy, and a demand from another pays
     * would leak one entity's headcount into another's pipeline.
     *
     * Null clears the link, which is deliberate — a spontaneous application that turns out not
     * to match the vacancy must be detachable.
     */
    private void applyRecruitmentDemand(Candidate candidate, Long demandId) {
        if (demandId == null) {
            candidate.setRecruitmentDemandId(null);
            return;
        }
        RecruitmentDemand demand = recruitmentDemandRepo.findById(demandId)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND,
                        "Demande de recrutement introuvable : id=" + demandId));

        if (demand.getStatut() != RecruitmentDemandStatus.APPROUVEE) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Seule une demande de recrutement approuvée peut recevoir des candidatures "
                  + "(demande " + demandId + " : " + demand.getStatut() + ").");
        }
        if (candidate.getPaysId() != null && demand.getPaysId() != null
                && !candidate.getPaysId().equals(demand.getPaysId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La demande de recrutement appartient à une autre entité.");
        }
        candidate.setRecruitmentDemandId(demandId);
    }

    /**
     * Sets the candidature's contract type — an CONTRACT_TYPE `configurable_list_values.id`.
     *
     * Null means "leave it alone", like every other field on the update DTO. There is no
     * "detach" here: a candidature without a contract type falls back to CDI everywhere
     * downstream ({@link ContractTypeBridge#resolveContractTypeCode}), so clearing it would
     * quietly turn a CDD offer into a CDI rather than leaving a blank.
     *
     * Validated rather than trusted — unlike creation, which writes the raw id straight
     * through the mapper. The id must be an ACTIVE value of the CONTRACT_TYPE list and
     * either global (`pays_id IS NULL`) or the candidate's own entity; otherwise a typo, a
     * stale dropdown or another entity's list would be persisted and only surface much later,
     * as a wrong contract at hire time.
     *
     * Refused outright once the candidate is HIRED: the employee profile and the signed
     * contract were derived from this field, so changing it afterwards makes the candidature
     * disagree with the dossier it produced. The type moves through a new contract on
     * `/rh/profiles/:id`, not here.
     */
    private void applyEmploymentType(Candidate candidate, Long employmentTypeId) {
        if (employmentTypeId == null
                || employmentTypeId.equals(candidate.getEmploymentTypeId())) {
            return;
        }
        if (candidate.getStatus() == CandidateStatus.HIRED) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Le type de contrat d'un candidat déjà embauché ne peut plus être modifié — "
                  + "passez par un nouveau contrat sur la fiche employé.");
        }

        Long listTypeId = listTypeRepo.findByCode("CONTRACT_TYPE")
                .map(com.daf360.rh.lists.ConfigurableListType::getId)
                .orElseThrow(() -> new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Liste CONTRACT_TYPE introuvable."));

        // The same query the dropdown is built from, so anything the user can pick is
        // accepted and anything else is not.
        boolean allowed = listValueRepo
                .findActiveByListTypeAndPays(listTypeId, candidate.getPaysId())
                .stream()
                .anyMatch(v -> v.getId().equals(employmentTypeId));
        if (!allowed) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Type de contrat invalide pour cette entité : id=" + employmentTypeId);
        }
        candidate.setEmploymentTypeId(employmentTypeId);
    }

    /** The vacancy's title for display — `jobExactTitle` when set, else the generic one. */
    private String demandTitle(Long demandId) {
        if (demandId == null) return null;
        return recruitmentDemandRepo.findById(demandId)
                .map(d -> d.getJobExactTitle() != null && !d.getJobExactTitle().isBlank()
                        ? d.getJobExactTitle() : d.getJobTitle())
                .orElse(null);
    }

    private CandidateResponse toFullResponse(Candidate candidate) {
        CandidateResponse response = mapper.toResponse(candidate);
        itProvRepo.findByCandidateId(candidate.getId())
                .map(prov -> {
                    ItProvisioningSummary summary = mapper.toItSummary(prov);
                    // The mapper ignores assetsProvided (it lives in it_assets, not on the
                    // provisioning row), so the candidate's IT tab always showed "null / 6".
                    summary.setAssetsProvided((int) itAssetRepo.findByProvisioningId(prov.getId()).stream()
                            .filter(a -> Boolean.TRUE.equals(a.getProvided()))
                            .count());
                    return summary;
                })
                .ifPresent(response::setItProvisioning);
        // The mapper ignores this (recruitmentDemandId is a plain Long, not an association it
        // can navigate), so it was always null. Filled here, where the repo is available.
        response.setRecruitmentDemandJobTitle(demandTitle(candidate.getRecruitmentDemandId()));
        if (candidate.getEmploymentTypeId() != null) {
            listValueRepo.findById(candidate.getEmploymentTypeId())
                    .ifPresent(v -> response.setEmploymentTypeLabel(
                            v.getLabelFr() != null ? v.getLabelFr() : v.getLabelEn()));
        }
        return response;
    }
}
