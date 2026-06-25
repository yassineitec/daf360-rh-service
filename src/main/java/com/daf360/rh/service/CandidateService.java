package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.candidate.*;
import com.daf360.rh.dto.lifecycle.CreateContractRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.lifecycle.ContractTypeBridge;
import com.daf360.rh.lifecycle.EmployeeLifecycleService;
import com.daf360.rh.mapper.CandidateMapper;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.DisciplineRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.GradeRepository;
import com.daf360.rh.repository.HrDepartmentRepository;
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

import com.daf360.rh.notification.RoutingContext;

import com.daf360.rh.dto.candidate.CandidateHistoryItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    private final CandidateRepository        candidateRepo;
    private final ItProvisioningRepository   itProvRepo;
    private final EmployeeProfileRepository  profileRepo;
    private final CandidateMapper            mapper;
    private final AuditService               auditService;
    private final AppProperties              appProperties;
    private final JdbcTemplate               jdbc;
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;
    private final EmployeeLifecycleService   lifecycleService;
    private final ContractTypeBridge         contractTypeBridge;
    private final com.daf360.rh.security.TenantService tenantService;

    // ── Dimension repos for FK resolution ────────────────────────────────────
    private final NationalityRepository           nationalityRepo;
    private final GradeRepository                 gradeRepo;
    private final DisciplineRepository            disciplineRepo;
    private final HrDepartmentRepository          departmentRepo;
    private final ConfigurableListValueRepository listValueRepo;

    // ── SQL constants ─────────────────────────────────────────────────────────

    /** Finds user IDs that hold a given permission for a given pays. */
    private static final String USERS_WITH_PERMISSION_SQL = """
        SELECT DISTINCT u.id
          FROM [dbo].[Users] u
          JOIN [dbo].[Roles] r       ON r.id    = u.role_id
          JOIN [dbo].[RolePermissions] rp ON rp.role_id = r.id
         WHERE rp.permission = ?
           AND u.pays_id     = ?
           AND (u.isActive = 1 OR u.isActive IS NULL)
        """;

    private static final String INSERT_NOTIFICATION_SQL = """
        INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at)
        VALUES (?, 'HR', ?, ?, 0, SYSDATETIMEOFFSET())
        """;

    // ── Public API ────────────────────────────────────────────────────────────

    public CandidateResponse createCandidate(CreateCandidateRequest request, Long actorUserId) {
        if (candidateRepo.existsByEmailPersonal(request.getEmailPersonal())) {
            throw new AppException(ErrorCode.CANDIDATE_EMAIL_DUPLICATE);
        }

        Candidate candidate = mapper.toEntity(request);
        applyDimensionFks(candidate,
                request.getNationalityId(), request.getAppliedGradeId(),
                request.getAppliedDisciplineId(), request.getDepartmentId());
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
        applyDimensionFks(candidate,
                request.getNationalityId(), request.getAppliedGradeId(),
                request.getAppliedDisciplineId(), request.getDepartmentId());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        auditService.log(actorUserId.toString(), "UPDATE", "CANDIDATE", candidate.getId(),
                before, "email=" + candidate.getEmailPersonal());

        return toFullResponse(candidate);
    }

    public CandidateResponse acceptCandidate(Long id, Long actorUserId) {
        Candidate candidate = findOrThrow(id);

        if (candidate.getStatus() != CandidateStatus.PENDING) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Seuls les candidats PENDING peuvent être acceptés");
        }

        candidate.setStatus(CandidateStatus.ACCEPTED);
        candidate.setAcceptedBy(actorUserId);
        candidate.setAcceptedAt(OffsetDateTime.now());
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidate = candidateRepo.save(candidate);

        // Create IT provisioning task
        ItProvisioning prov = ItProvisioning.builder()
                .candidateId(candidate.getId())
                .status(ItProvisioningStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        itProvRepo.save(prov);

        // Notify IT and HR via routing rules (CANDIDATE_ACCEPTED event)
        notificationRoutingService.resolveAndDispatch(
                RoutingContext.builder()
                        .eventCode("CANDIDATE_ACCEPTED")
                        .paysId(candidate.getPaysId())
                        .templateVars(Map.of(
                                "candidateName", candidate.getFirstName() + " " + candidate.getLastName(),
                                "firstName",     candidate.getFirstName(),
                                "lastName",      candidate.getLastName()
                        ))
                        .build()
        );

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

        String contractTypeCode = req.getContractTypeCode() != null && !req.getContractTypeCode().isBlank()
                ? req.getContractTypeCode()
                : contractTypeBridge.resolveContractTypeCode(candidate.getEmploymentTypeId());

        if (NEEDS_END_DATE.contains(contractTypeCode) && req.getDateFinPrevue() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La date de fin est obligatoire pour le type de contrat : " + contractTypeCode);
        }

        CreateContractRequest contractReq = new CreateContractRequest();
        contractReq.setEmployeeProfileId(profile.getId());
        contractReq.setPaysId(candidate.getPaysId());
        contractReq.setContractTypeCode(contractTypeCode);
        contractReq.setDateDebut(req.getHireDate());
        contractReq.setDateFinPrevue(req.getDateFinPrevue());
        contractReq.setManagerProfile(req.isManagerProfile());

        var created = lifecycleService.createContractFromBridge(contractReq, actorUserId);

        candidate.setStatus(CandidateStatus.HIRED);
        candidate.setUpdatedAt(OffsetDateTime.now());
        candidateRepo.save(candidate);

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
                                                   Long paysId, String search, Pageable pageable) {
        // Enforce tenant isolation: non-admin users can only see their own entity's candidates.
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;

        if (stage != null && !stage.isBlank()) {
            List<CandidateStatus> statuses = stageToStatuses(stage);
            return candidateRepo.searchByStatusesAndSearch(statuses, resolvedPaysId, search, pageable)
                    .map(mapper::toListItem);
        }
        return candidateRepo.searchPaged(status, resolvedPaysId, search, pageable)
                .map(mapper::toListItem);
    }

    private static List<CandidateStatus> stageToStatuses(String stage) {
        return switch (stage.toUpperCase()) {
            case "CANDIDATURE"          -> List.of(CandidateStatus.PENDING);
            case "SCREENING_RH"         -> List.of(CandidateStatus.ACCEPTED, CandidateStatus.HR_IN_PROGRESS);
            case "ENTRETIEN_TECHNIQUE"  -> List.of(CandidateStatus.IT_IN_PROGRESS);
            case "OFFRE_ENVOYEE"        -> List.of(CandidateStatus.EMAIL_RECEIVED);
            case "RECRUTE"              -> List.of(CandidateStatus.HIRED);
            case "REJETE"               -> List.of(CandidateStatus.REJECTED, CandidateStatus.ARCHIVED);
            default                     -> List.of(CandidateStatus.values());
        };
    }

    @Transactional(readOnly = true)
    public CandidateResponse getCandidate(Long id) {
        return toFullResponse(findOrThrow(id));
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

    // ── History ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CandidateHistoryItem> getHistory(Long candidateId) {
        findOrThrow(candidateId);
        String sql = "SELECT al.id, al.timestamp, al.action, al.new_value, " +
                     "al.user_id as actor_id, u.fullName as actor_name " +
                     "FROM audit_log al " +
                     "LEFT JOIN [dbo].[Users] u ON u.id = TRY_CAST(al.user_id AS BIGINT) " +
                     "WHERE al.entity_type = 'CANDIDATE' " +
                     "AND al.entity_id = ? " +
                     "ORDER BY al.timestamp DESC";
        return jdbc.query(sql, (rs, rowNum) -> {
            String action  = rs.getString("action");
            String newVal  = rs.getString("new_value");
            String actorId = rs.getString("actor_id");
            java.sql.Timestamp ts = rs.getTimestamp("timestamp");
            return CandidateHistoryItem.builder()
                .id(rs.getLong("id"))
                .timestamp(ts != null ? ts.toInstant().toString() : null)
                .action(action)
                .actionLabel(mapHistoryActionLabel(action))
                .performedByUserId(actorId != null ? safeParseL(actorId) : null)
                .performedByName(rs.getString("actor_name"))
                .comment(extractHistoryComment(newVal))
                .resultingStatus(extractHistoryStatus(newVal))
                .build();
        }, candidateId.toString());
    }

    private String mapHistoryActionLabel(String action) {
        if (action == null) return "—";
        return switch (action) {
            case "CREATE_CANDIDATE", "CREATE" -> "Candidature créée";
            case "UPDATE_CANDIDATE", "UPDATE" -> "Profil modifié";
            case "ACCEPT_CANDIDATE", "ACCEPT" -> "Statut : Accepté(e)";
            case "REJECT_CANDIDATE", "REJECT" -> "Statut : Refusé(e)";
            case "UPDATE_IT_PROVISIONING"      -> "Provisioning IT mis à jour";
            case "SUBMIT_MS365_EMAIL"          -> "Compte Microsoft 365 créé";
            case "COMPLETE_IT_PROVISIONING"    -> "Provisioning IT terminé";
            case "COMPLETE_ONBOARDING_PROFILE" -> "Dossier RH complété";
            case "UPLOAD_CV"                   -> "CV téléversé";
            default                            -> action;
        };
    }

    private String extractHistoryComment(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(v);
            if (n.has("comment")) return n.get("comment").asText();
        } catch (Exception ignored) {}
        if (v.contains("comment=")) {
            int s = v.indexOf("comment=") + 8, e = v.indexOf(";", s);
            return e == -1 ? v.substring(s) : v.substring(s, e);
        }
        return v.length() > 80 ? null : v;
    }

    private String extractHistoryStatus(String v) {
        if (v == null) return null;
        return v.startsWith("status=") ? v.replace("status=", "") : null;
    }

    private Long safeParseL(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

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

    private CandidateResponse toFullResponse(Candidate candidate) {
        CandidateResponse response = mapper.toResponse(candidate);
        itProvRepo.findByCandidateId(candidate.getId())
                .map(mapper::toItSummary)
                .ifPresent(response::setItProvisioning);
        if (candidate.getEmploymentTypeId() != null) {
            listValueRepo.findById(candidate.getEmploymentTypeId())
                    .ifPresent(v -> response.setEmploymentTypeLabel(
                            v.getLabelFr() != null ? v.getLabelFr() : v.getLabelEn()));
        }
        return response;
    }

    private void notifyUsersWithPermission(String permission, Long paysId, String title, String message) {
        try {
            List<Long> userIds = jdbc.queryForList(USERS_WITH_PERMISSION_SQL, Long.class, permission, paysId);
            for (Long uid : userIds) {
                jdbc.update(INSERT_NOTIFICATION_SQL, uid, title, message);
            }
        } catch (Exception ex) {
            log.error("Failed to send notifications for permission={} pays={}: {}", permission, paysId, ex.getMessage());
        }
    }
}
