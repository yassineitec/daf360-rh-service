package com.daf360.rh.service;

import com.daf360.rh.common.GenderNormalizer;
import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.profile.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.mapper.EmployeeProfileMapper;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.sharepoint.EmployeeFolderResolver;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeProfileService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /**
     * Permission codes that grant access to sensitive PII and financial fields.
     */
    private static final Set<String> SENSITIVE_PERMISSIONS =
            Set.of("HR_UPDATE_PROFILE", "HR_CREATE_PROFILE", "HR_ARCHIVE_PROFILE",
                   "HR_ADMIN_ROLES", "VIEW_CANDIDATES");

    private final EmployeeProfileRepository profileRepository;
    private final EmployeeProfileMapper     mapper;
    private final AuditService              auditService;
    private final JdbcTemplate              jdbcTemplate;
    private final ObjectMapper              objectMapper;
    private final AppProperties             appProperties;
    private final com.daf360.rh.security.TenantService tenantService;

    // ── SharePoint (profile photo mirroring, cf. spec 2026-08-18) ─────────────
    private final GraphSharePointService graphSharePointService;
    private final EmployeeFolderResolver employeeFolderResolver;

    // ── Dimension repos injected for FK lookups ───────────────────────────────
    private final GradeRepository        gradeRepo;
    private final DisciplineRepository   disciplineRepo;
    private final NogLevelRepository     nogLevelRepo;
    private final HrDepartmentRepository departmentRepo;
    private final BankRepository         bankRepo;
    private final NationalityRepository        nationalityRepo;
    private final WorkingTimeRegimeRepository  regimeRepo;

    // ── Create ────────────────────────────────────────────────────────────────

    public EmployeeProfileResponseDto createProfile(EmployeeProfileCreateDto dto, Authentication auth) {

        if (profileRepository.existsByUserId(dto.getUserId())) {
            throw new AppException(
                    com.daf360.rh.exception.ErrorCode.ALREADY_EXISTS,
                    "Un profil existe déjà pour cet utilisateur (userId=" + dto.getUserId() + ")");
        }

        int existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[Users] WHERE employee_id = ?",
                Integer.class, dto.getEmployeeId());
        if (existing > 0) {
            throw new AppException(
                    com.daf360.rh.exception.ErrorCode.ALREADY_EXISTS,
                    "L'identifiant employé " + dto.getEmployeeId() + " est déjà utilisé");
        }

        EmployeeProfile profile = mapper.toEntity(dto);
        profile.setCreatedAt(OffsetDateTime.now(PARIS));

        // Resolve FK dimension fields from IDs in the create DTO
        applyDimensionFks(profile, dto.getNationalityId(), dto.getGradeId(),
                dto.getDisciplineId(), dto.getNogLevelId(), dto.getDepartmentId(), dto.getBankId());

        EmployeeProfile saved = profileRepository.save(profile);

        jdbcTemplate.update("UPDATE [dbo].[Users] SET employee_id = ? WHERE id = ?",
                dto.getEmployeeId(), dto.getUserId());

        auditService.log(actorId(auth), "CREATE_PROFILE", "EmployeeProfile", saved.getId(),
                null, safeJson(saved));
        log.info("Created employee profile id={} userId={}", saved.getId(), saved.getUserId());

        return toResponseDto(saved, auth);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EmployeeProfileResponseDto getProfile(Long id, Authentication auth) {
        EmployeeProfile profile = findOrThrow(id);
        return toResponseDto(profile, auth);
    }

    @Transactional(readOnly = true)
    public Page<EmployeeProfileSummaryDto> listProfiles(ProfileFilterDto filter, Pageable pageable) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : filter.getPaysId();
        return profileRepository.search(
                resolvedPaysId,
                filter.getStatus(),
                filter.getDepartment(),
                filter.getGrade(),
                filter.getContract(),
                filter.getSearch(),
                pageable
        ).map(mapper::toSummaryDto);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public EmployeeProfileResponseDto updateProfile(Long id, EmployeeProfileUpdateDto dto, Authentication auth) {
        EmployeeProfile profile = findOrThrow(id);

        // Guard sensitive fields — only HR_MANAGER / FINANCE_OFFICER may update them
        if (!hasSensitiveAccess(auth)) {
            dto.setIban(null);
            dto.setBankAccountNumber(null);
            dto.setRib(null);
            dto.setBankId(null);
            dto.setSocialSecurityNumber(null);
            dto.setNationalId(null);
            dto.setPassportNumber(null);
            dto.setTaxId(null);
            dto.setCnssNumber(null);
            dto.setCnssAffiliationDate(null);
        }

        String before = safeJson(profile);

        // Apply non-dimension scalar fields via MapStruct (PATCH semantics)
        mapper.updateEntityFromDto(dto, profile);

        // Keep gender canonical (MALE/FEMALE/OTHER/UNSPECIFIED) regardless of what the
        // client sent — the detail edit form historically posted French labels.
        if (dto.getGender() != null) {
            profile.setGender(GenderNormalizer.normalize(profile.getGender()));
        }

        // Resolve and apply dimension FK fields explicitly.
        // The frontend always echoes the form's full current state for these ids, so an
        // explicit null means "clear this relation", not "leave untouched" — unlike the
        // scalar fields above, which MapStruct ignores when null (see class doc).
        if (dto.getNationalityId() != null) {
            nationalityRepo.findById(dto.getNationalityId()).ifPresent(profile::setNationality);
        } else {
            profile.setNationality(null);
        }
        if (dto.getGradeId() != null) {
            gradeRepo.findById(dto.getGradeId()).ifPresent(profile::setGrade);
        } else {
            profile.setGrade(null);
        }
        if (dto.getDisciplineId() != null) {
            disciplineRepo.findById(dto.getDisciplineId()).ifPresent(profile::setDiscipline);
        } else {
            profile.setDiscipline(null);
        }
        if (dto.getNogLevelId() != null) {
            nogLevelRepo.findById(dto.getNogLevelId()).ifPresent(profile::setNogLevel);
        } else {
            profile.setNogLevel(null);
        }
        if (dto.getDepartmentId() != null) {
            departmentRepo.findById(dto.getDepartmentId()).ifPresent(profile::setDepartment);
        } else {
            profile.setDepartment(null);
        }
        // Bank is sensitive — the guard above always nulls dto.bankId for non-privileged
        // callers, so only treat null as "clear" when the caller actually has sensitive
        // access; otherwise an unrelated field-only edit would silently wipe the bank link.
        if (hasSensitiveAccess(auth)) {
            if (dto.getBankId() != null) {
                bankRepo.findById(dto.getBankId()).ifPresent(profile::setBank);
            } else {
                profile.setBank(null);
            }
        }

        if (dto.getSalaireNetCandidat() != null) profile.setSalaireNetCandidat(dto.getSalaireNetCandidat());
        if (dto.getSalaireNetRh()       != null) profile.setSalaireNetRh(dto.getSalaireNetRh());

        profile.setUpdatedAt(OffsetDateTime.now(PARIS));
        EmployeeProfile saved = profileRepository.save(profile);

        String afterJson = safeJson(saved);
        String afterWithReason = "{\"reason\":\"" + dto.getReason().replace("\"", "\\\"")
                + "\",\"data\":" + afterJson + "}";
        auditService.log(actorId(auth), "UPDATE_PROFILE", "EmployeeProfile", id,
                before, afterWithReason);

        return toResponseDto(saved, auth);
    }

    // ── Lifecycle transition ──────────────────────────────────────────────────

    public EmployeeProfileResponseDto transitionLifecycle(Long id, LifecycleTransitionDto dto, Authentication auth) {
        EmployeeProfile profile = findOrThrow(id);
        LifecycleStatus current = profile.getLifecycleStatus();
        LifecycleStatus next    = dto.getNewStatus();

        if (!current.canTransitionTo(next)) {
            throw new AppException(
                    com.daf360.rh.exception.ErrorCode.LIFECYCLE_TRANSITION_INVALID,
                    "Transition interdite: " + current + " → " + next);
        }

        if (next == LifecycleStatus.ARCHIVED) {
            pseudonymise(profile);
        }

        profile.setLifecycleStatus(next);
        profile.setUpdatedAt(OffsetDateTime.now(PARIS));
        EmployeeProfile saved = profileRepository.save(profile);

        auditService.log(actorId(auth), "LIFECYCLE_" + next, "EmployeeProfile", id,
                current.name(), next.name() + " | " + dto.getReason());

        return toResponseDto(saved, auth);
    }

    @Transactional(readOnly = true)
    public Long getProfilePaysId(Long profileId) {
        return findOrThrow(profileId).getPaysId();
    }

    @Transactional(readOnly = true)
    public Long getCandidateId(Long profileId) {
        return findOrThrow(profileId).getCandidateId();
    }

    @Transactional(readOnly = true)
    public Long getUserId(Long profileId) {
        return findOrThrow(profileId).getUserId();
    }

    public void transitionLifecycleByActor(Long id, LifecycleTransitionDto dto, Long actorUserId) {
        EmployeeProfile profile = findOrThrow(id);
        LifecycleStatus current = profile.getLifecycleStatus();
        LifecycleStatus next    = dto.getNewStatus();

        if (!current.canTransitionTo(next)) {
            throw new AppException(
                    com.daf360.rh.exception.ErrorCode.LIFECYCLE_TRANSITION_INVALID,
                    "Transition interdite: " + current + " → " + next);
        }
        if (next == LifecycleStatus.ARCHIVED) {
            pseudonymise(profile);
        }
        profile.setLifecycleStatus(next);
        profile.setUpdatedAt(OffsetDateTime.now(PARIS));
        profileRepository.save(profile);

        String actor = actorUserId != null ? actorUserId.toString() : "SYSTEM";
        auditService.log(actor, "LIFECYCLE_" + next, "EmployeeProfile", id,
                current.name(), next.name() + " | " + dto.getReason());
    }

    // ── Archive (soft delete + pseudonymise) ─────────────────────────────────

    public void archiveProfile(Long id, Authentication auth) {
        LifecycleTransitionDto dto = new LifecycleTransitionDto();
        dto.setNewStatus(LifecycleStatus.ARCHIVED);
        dto.setReason("Archive via DELETE endpoint");
        transitionLifecycle(id, dto, auth);
    }

    // ── Employee list (Users LEFT JOIN employee_profiles) ────────────────────
    // The V23 dimension tables (departments, grades) ARE joined: they are already
    // relied on by DashboardService, PdfDocumentService and getFilterOptions().
    // Filtering is by their French label because that is what filter-options
    // hands the client for those two fields; `pays` filters by numeric id.

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<EmployeeListItemDto> listAllEmployees(
            ProfileFilterDto filter, Pageable pageable) {

        String search     = (filter.getSearch() != null && !filter.getSearch().isBlank())
                            ? filter.getSearch().trim() : null;
        String searchLike = search != null ? "%" + search + "%" : null;
        Long   effectivePaysId = tenantService.getEffectivePaysId();
        Long   paysId          = effectivePaysId != null ? effectivePaysId : filter.getPaysId();
        String status     = (filter.getStatus() != null && !filter.getStatus().isBlank())
                            ? filter.getStatus() : null;
        String department = (filter.getDepartment() != null && !filter.getDepartment().isBlank())
                            ? filter.getDepartment().trim() : null;
        String grade      = (filter.getGrade() != null && !filter.getGrade().isBlank())
                            ? filter.getGrade().trim() : null;
        String contract   = (filter.getContract() != null && !filter.getContract().isBlank())
                            ? filter.getContract().trim() : null;
        java.time.LocalDate hireFrom = filter.getHireDateFrom();
        java.time.LocalDate hireTo   = filter.getHireDateTo();

        int offset   = (int) pageable.getOffset();
        int pageSize = pageable.getPageSize();

        String baseFrom  =
            "FROM [dbo].[Users] u " +
            "LEFT JOIN [dbo].[pays] p ON p.id = u.pays_id " +
            "LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id AND (r.deleted = 0 OR r.deleted IS NULL) " +
            "LEFT JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id AND ep.deleted = 0 " +
            "LEFT JOIN [dbo].[departments] d ON d.id = ep.department_id " +
            "LEFT JOIN [dbo].[grades] g ON g.id = ep.grade_id " +
            "LEFT JOIN [dbo].[disciplines] disc ON disc.id = ep.discipline_id " +
            "LEFT JOIN [dbo].[nog_levels] nog ON nog.id = ep.nog_level_id ";

        String baseWhere =
            "WHERE (u.isActive = 1 OR u.isActive IS NULL) " +
            (searchLike != null ? "AND (u.fullName LIKE ? OR u.username LIKE ?) " : "") +
            (paysId     != null ? "AND u.pays_id = ? " : "") +
            (status     != null ? "AND ep.lifecycle_status = ? " : "") +
            (department != null ? "AND d.label_fr = ? " : "") +
            (grade      != null ? "AND g.label_fr = ? " : "") +
            (contract   != null ? "AND ep.contract_type = ? " : "") +
            (hireFrom   != null ? "AND ep.hire_date >= ? " : "") +
            (hireTo     != null ? "AND ep.hire_date <= ? " : "");

        List<Object> args = new ArrayList<>();
        if (searchLike != null) { args.add(searchLike); args.add(searchLike); }
        if (paysId     != null) { args.add(paysId); }
        if (status     != null) { args.add(status); }
        if (department != null) { args.add(department); }
        if (grade      != null) { args.add(grade); }
        if (contract   != null) { args.add(contract); }
        if (hireFrom   != null) { args.add(java.sql.Date.valueOf(hireFrom)); }
        if (hireTo     != null) { args.add(java.sql.Date.valueOf(hireTo)); }

        String listSql =
            "SELECT ep.id AS profile_id, u.id AS user_id, u.fullName AS full_name, " +
            "COALESCE(u.email, u.username) AS email, u.employee_id AS employee_id, u.pays_id AS pays_id, " +
            "p.french_label AS pays_label, u.role_id AS role_id, r.frenchName AS role_name, " +
            "ep.lifecycle_status AS lifecycle_status, ep.contract_type AS contract_type, " +
            "ep.hire_date AS hire_date, ep.photo_url AS photo_url, ep.gender AS gender, " +
            "d.label_fr AS department, g.label_fr AS grade, " +
            "disc.label_fr AS discipline, nog.label_fr AS nog_level " +
            baseFrom + baseWhere +
            // Tiebreakers make OFFSET/FETCH deterministic: fullName is not unique
            // (duplicate names exist), and a user could join >1 profile row — without
            // u.id + ep.id the paged order shuffles, so the same row can return a
            // different profile/gender (or null) between identical requests.
            "ORDER BY u.fullName, u.id, ep.id " +
            "OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";

        List<EmployeeListItemDto> rows = jdbcTemplate.query(
            listSql,
            (rs, rowNum) -> {
                java.sql.Date sqlDate = rs.getDate("hire_date");
                return EmployeeListItemDto.builder()
                    .profileId(rs.getObject("profile_id") != null ? rs.getLong("profile_id") : null)
                    .userId(rs.getLong("user_id"))
                    .fullName(rs.getString("full_name"))
                    .email(rs.getString("email"))
                    .employeeId(rs.getString("employee_id"))
                    .paysId(rs.getObject("pays_id") != null ? rs.getLong("pays_id") : null)
                    .paysLabel(rs.getString("pays_label"))
                    .roleId(rs.getObject("role_id") != null ? rs.getLong("role_id") : null)
                    .roleName(rs.getString("role_name"))
                    .lifecycleStatus(rs.getString("lifecycle_status"))
                    .contractType(rs.getString("contract_type"))
                    .department(rs.getString("department"))
                    .grade(rs.getString("grade"))
                    .discipline(rs.getString("discipline"))
                    .nogLevel(rs.getString("nog_level"))
                    .hireDate(sqlDate != null ? sqlDate.toLocalDate() : null)
                    .photoUrl(rs.getString("photo_url"))
                    .gender(rs.getString("gender"))
                    .hasProfile(rs.getObject("profile_id") != null)
                    .build();
            },
            args.toArray());

        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) " + baseFrom + baseWhere,
            Integer.class,
            args.toArray());

        long total = count != null ? count : 0;
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, total);
    }

    // ── Filter options for profile list dropdowns ─────────────────────────────

    @Transactional(readOnly = true)
    public com.daf360.rh.dto.profile.FilterOptionsDto getFilterOptions() {
        // Country: value is the numeric pays_id, because /employees filters on
        // `u.pays_id`. Returning the label as the value is what broke this filter.
        List<com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto> paysList =
            jdbcTemplate.query(
                "SELECT DISTINCT p.id, p.french_label " +
                "FROM [dbo].[pays] p " +
                "JOIN [dbo].[Users] u ON u.pays_id = p.id " +
                "WHERE (u.isActive = 1 OR u.isActive IS NULL) " +
                "  AND p.french_label IS NOT NULL " +
                "ORDER BY p.french_label",
                (rs, i) -> new com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto(
                    String.valueOf(rs.getLong("id")), rs.getString("french_label")));

        // Department / grade: value IS the label — /employees matches on label_fr,
        // since employee_profiles rows may predate the dimension FKs.
        List<com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto> departmentList =
            jdbcTemplate.query(
                "SELECT DISTINCT d.label_fr " +
                "FROM [dbo].[departments] d " +
                "WHERE d.is_active = 1 AND d.label_fr IS NOT NULL " +
                "ORDER BY d.label_fr",
                (rs, i) -> new com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto(
                    rs.getString("label_fr"), rs.getString("label_fr")));

        List<com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto> gradeList =
            jdbcTemplate.query(
                "SELECT DISTINCT g.label_fr " +
                "FROM [dbo].[grades] g " +
                "WHERE g.is_active = 1 AND g.label_fr IS NOT NULL " +
                "ORDER BY g.label_fr",
                (rs, i) -> new com.daf360.rh.dto.profile.FilterOptionsDto.FilterOptionDto(
                    rs.getString("label_fr"), rs.getString("label_fr")));

        // Raw codes only — contract_type is a free varchar holding two generations
        // of codes (PERMANENT/FIXED_TERM… and CDI/CDD/…); the client translates.
        List<String> contractTypes = jdbcTemplate.queryForList(
            "SELECT DISTINCT ep.contract_type " +
            "FROM [dbo].[employee_profiles] ep " +
            "WHERE ep.deleted = 0 AND ep.contract_type IS NOT NULL AND ep.contract_type <> '' " +
            "ORDER BY ep.contract_type",
            String.class);

        return new com.daf360.rh.dto.profile.FilterOptionsDto(
            departmentList, gradeList, paysList, contractTypes);
    }

    // ── Update Users table fields ─────────────────────────────────────────────

    public void updateUserFields(Long userId, java.util.Map<String, Object> body, Long actorId) {
        List<String> updates = new ArrayList<>();
        List<Object> args    = new ArrayList<>();

        if (body.containsKey("fullName")) {
            updates.add("fullName = ?");
            args.add(body.get("fullName"));
        }
        if (body.containsKey("roleId")) {
            Long newRoleId = body.get("roleId") != null
                             ? Long.valueOf(body.get("roleId").toString()) : null;
            updates.add("role_id = ?");
            args.add(newRoleId);
        }

        if (updates.isEmpty()) return;
        args.add(userId);
        jdbcTemplate.update(
            "UPDATE [dbo].[Users] SET " + String.join(", ", updates) + " WHERE id = ?",
            args.toArray());

        auditService.log(
            actorId != null ? actorId.toString() : "SYSTEM",
            "UPDATE_USER_FIELDS", "User", userId, null, body.toString());
    }

    // ── Photo upload / serve ──────────────────────────────────────────────────

    /**
     * Upload a profile photo for an employee.
     * Stores file at {storagePath}/profiles/{profileId}/{uuid}.{ext}
     * Updates employee_profiles.photo_url to the API path /api/hr/profiles/{id}/photo
     */
    public EmployeeProfileResponseDto uploadPhoto(Long profileId,
                                                   org.springframework.web.multipart.MultipartFile file,
                                                   Authentication auth) {
        EmployeeProfile profile = findOrThrow(profileId);

        // Validate content type
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/jpeg")
                && !contentType.startsWith("image/png")
                && !contentType.startsWith("image/webp"))) {
            throw new AppException(com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Type de fichier non supporté. Formats acceptés : JPEG, PNG, WebP.");
        }

        // Validate size (max 3 MB)
        if (file.getSize() > 3 * 1024 * 1024) {
            throw new AppException(com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Photo trop volumineuse. Taille maximale : 3 Mo.");
        }

        try {
            byte[] bytes = file.getBytes();

            // Build storage path
            String ext = contentType.contains("png") ? ".png"
                       : contentType.contains("webp") ? ".webp" : ".jpg";
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());
            java.nio.file.Files.createDirectories(dir);
            String filename = java.util.UUID.randomUUID() + ext;
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.write(target, bytes);

            // Store the API path as photo_url (frontend will prefix with hrApiUrl)
            profile.setPhotoUrl("/api/hr/profiles/" + profileId + "/photo");
            profile.setUpdatedAt(java.time.OffsetDateTime.now());

            // Best-effort mirror to SharePoint — mirrorPhotoToSharePoint() never throws
            // (see its own try/catch), so it can never block the save below, even if
            // SharePoint is completely unreachable.
            mirrorPhotoToSharePoint(profile, ext, bytes);

            profileRepository.save(profile);

            auditService.log(actorId(auth), "UPLOAD_PHOTO", "EmployeeProfile", profileId,
                    null, "Photo mise à jour");
            log.info("Photo uploaded for profileId={}", profileId);

            return toResponseDto(profileRepository.findById(profileId).orElseThrow(), auth);

        } catch (java.io.IOException e) {
            log.error("Failed to store photo for profile {}: {}", profileId, e.getMessage());
            throw new AppException(com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Erreur lors du téléversement de la photo.");
        }
    }

    /**
     * Resolve avatars for a batch of <b>user</b> ids.
     *
     * <p>Exists because every other module stores a person as a user id — facturation's
     * {@code affaires.responsable_user_id}, for one — while the photo hangs off the RH
     * profile and is served by <b>profile</b> id. Without this, a consumer had to either
     * search profiles by name (fragile: homonyms, paging) or fetch a full profile per
     * person just to read one column.
     *
     * <p>Batched on purpose: a card showing a project team asks once for N people rather
     * than firing N requests. Unknown or soft-deleted users are simply absent from the
     * result — callers key by {@code userId} and fall back to initials, so a missing row
     * is a normal outcome, not an error.
     *
     * <p>JdbcTemplate rather than JPA for the same reason as {@code listAllEmployees}:
     * {@code [dbo].[Users]} is not a JPA entity in this service, and {@code fullName}
     * lives there.
     */
    @Transactional(readOnly = true)
    public List<EmployeeAvatarDto> resolveAvatars(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();

        // Bound the batch: this is called from a page render, and an unbounded IN list
        // would let a caller turn one request into a full table scan.
        List<Long> ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().limit(100).toList();
        if (ids.isEmpty()) return List.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql =
            "SELECT u.id AS user_id, ep.id AS profile_id, ep.photo_url AS photo_url, " +
            "ep.updated_at AS updated_at, u.fullName AS full_name, ep.gender AS gender " +
            "FROM [dbo].[Users] u " +
            // LEFT JOIN, not JOIN: a user with no RH profile is a legitimate answer
            // (fullName for the initials, no photo) — inner-joining would drop them and
            // the caller would show an empty circle instead of initials.
            "LEFT JOIN employee_profiles ep ON ep.user_id = u.id AND ep.deleted = 0 " +
            "WHERE u.id IN (" + placeholders + ")";

        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> {
                // getTimestamp rather than getObject(..., OffsetDateTime.class): the column is
                // DATETIMEOFFSET and only the instant matters here, since this is a version token.
                java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
                return new EmployeeAvatarDto(
                    rs.getLong("user_id"),
                    rs.getObject("profile_id") != null ? rs.getLong("profile_id") : null,
                    rs.getString("photo_url"),
                    updatedAt != null ? updatedAt.getTime() : null,
                    rs.getString("full_name"),
                    rs.getString("gender")
                );
            },
            ids.toArray());
    }

    /**
     * Serve the profile photo as bytes, or null when there is nothing to serve.
     *
     * <p><b>Null is a normal answer</b>, not a failure: the caller turns it into a cached
     * 404 and the UI falls back to initials. So every failure mode has to come out of here
     * as null — a photo that cannot be read must never become a 500 on a page that merely
     * wanted to draw a face.
     *
     * <p>Two bugs this used to have, both of which surfaced as HTTP 500 once avatars
     * started being requested in bulk (the affaire "Équipe projet" tile):
     * <ul>
     *   <li>{@code Files.list()} returns a stream that <b>must be closed</b> — it holds an
     *       open directory handle. Leaking one per request exhausts file descriptors, and
     *       on Windows it also keeps the directory locked so the next upload cannot write
     *       into it. Now in a try-with-resources.</li>
     *   <li>Lazy traversal of that stream throws {@link java.io.UncheckedIOException},
     *       which is a <b>RuntimeException</b> and therefore walked straight past the old
     *       {@code catch (IOException)} and out of the method. Same for a storage path that
     *       is not a valid path ({@link java.nio.file.InvalidPathException}). The catch is
     *       now on {@code Exception}.</li>
     * </ul>
     */
    // NOT_SUPPORTED, not readOnly: this method can self-heal via a Graph HTTP round-trip
    // (fetchAndCachePhotoFromSharePoint) plus a disk write, and it fires on every avatar
    // render — holding a pooled DB connection open for that whole span (as readOnly would)
    // risks starving the pool under concurrent cache misses, e.g. right after a deploy or
    // a cleared cache. NOT_SUPPORTED suspends any active transaction for the method's
    // duration; profileRepository.findById(...) and jdbcTemplate.queryForObject(...) each
    // still check out and release their own short-lived connection per call.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public byte[] servePhoto(Long profileId) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());

            byte[] cached = readMostRecentLocalFile(dir);
            if (cached != null) return cached;

            // Cache miss (no local file at all) — self-heal from SharePoint if
            // configured for this employee, then cache the result for next time.
            return fetchAndCachePhotoFromSharePoint(profileId, dir);

        } catch (Exception e) {
            // Exception, not IOException: see the class-level note above.
            log.warn("Cannot serve photo for profile {}: {}: {}",
                    profileId, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private byte[] readMostRecentLocalFile(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(dir)) return null;

        java.nio.file.Path latest;
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(dir)) {
            latest = entries
                    .filter(java.nio.file.Files::isRegularFile)
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p).toMillis(); }
                        catch (java.io.IOException e) { return 0L; }
                    }))
                    .orElse(null);
        }
        return latest != null ? java.nio.file.Files.readAllBytes(latest) : null;
    }

    /** Auto-réparation du cache local : ne s'exécute que quand aucun fichier local
     * n'existe (nouveau serveur, cache vidé, etc.) — jamais sur le chemin rapide
     * habituel (readMostRecentLocalFile ci-dessus retourne alors directement).
     * Reconstruit le même chemin déterministe que celui utilisé à l'upload
     * (mirrorPhotoToSharePoint) ; l'extension n'étant pas persistée séparément, les
     * trois extensions supportées sont essayées dans l'ordre. Toute exception ici
     * remonte au catch-all de servePhoto() ci-dessus — pas besoin d'un try/catch
     * séparé, contrairement à mirrorPhotoToSharePoint qui est appelé depuis
     * uploadPhoto (dont le catch ne couvre que IOException). */
    private byte[] fetchAndCachePhotoFromSharePoint(Long profileId, java.nio.file.Path dir) {
        EmployeeProfile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null) return null;

        EmployeeSharepointFolder folder =
                resolveEmployeeSharepointFolder(profile.getPaysId(), profile.getUserId());
        if (folder == null) return null;

        String basePath = folder.locationTemplate().replace("{employeeFolder}", folder.employeeFolder());
        for (String candidate : java.util.List.of("Photo.jpg", "Photo.png", "Photo.webp")) {
            java.util.Optional<byte[]> content =
                    graphSharePointService.downloadFile(basePath + "/" + candidate);
            if (content.isPresent()) {
                cacheLocally(dir, candidate, content.get());
                return content.get();
            }
        }
        return null;
    }

    private void cacheLocally(java.nio.file.Path dir, String sharePointFileName, byte[] content) {
        try {
            java.nio.file.Files.createDirectories(dir);
            String ext = sharePointFileName.substring(sharePointFileName.lastIndexOf('.'));
            java.nio.file.Path target = dir.resolve(java.util.UUID.randomUUID() + ext);
            java.nio.file.Files.write(target, content);
        } catch (java.io.IOException e) {
            // Best-effort caching only — the caller already has the bytes to serve
            // regardless of whether this local write succeeds.
            log.warn("Could not cache SharePoint photo locally at {}: {}", dir, e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyDimensionFks(EmployeeProfile profile,
                                   Long nationalityId, Long gradeId, Long disciplineId,
                                   Long nogLevelId, Long departmentId, Long bankId) {
        if (nationalityId != null) nationalityRepo.findById(nationalityId).ifPresent(profile::setNationality);
        if (gradeId       != null) gradeRepo.findById(gradeId).ifPresent(profile::setGrade);
        if (disciplineId  != null) disciplineRepo.findById(disciplineId).ifPresent(profile::setDiscipline);
        if (nogLevelId    != null) nogLevelRepo.findById(nogLevelId).ifPresent(profile::setNogLevel);
        if (departmentId  != null) departmentRepo.findById(departmentId).ifPresent(profile::setDepartment);
        if (bankId        != null) bankRepo.findById(bankId).ifPresent(profile::setBank);
    }

    // ── SharePoint mirroring (profile photo) ───────────────────────────────────

    /** Best-effort: upload profile photo bytes to SharePoint under the employee's
     * folder, using a FIXED filename per employee ("Photo.jpg"/.png/.webp) so a
     * re-upload cleanly overwrites the previous SharePoint copy instead of
     * accumulating versions the way local disk currently does. Never throws — any
     * failure (unconfigured, no per-pays location, ambiguous employee name,
     * network/auth) just leaves photoSharepointUrl unset; the local save has
     * already succeeded regardless of what happens in here. */
    private void mirrorPhotoToSharePoint(EmployeeProfile profile, String ext, byte[] bytes) {
        try {
            EmployeeSharepointFolder folder =
                    resolveEmployeeSharepointFolder(profile.getPaysId(), profile.getUserId());
            if (folder == null) return;

            String fixedFileName = "Photo" + ext;
            String basePath = folder.locationTemplate().replace("{employeeFolder}", folder.employeeFolder());
            // Clean up any stale copy left by a PREVIOUS upload in a different format
            // (e.g. first upload was .jpg, this one is .png) — otherwise both files
            // coexist forever, defeating the fixed-filename design goal and confusing
            // Task 5's servePhoto self-heal (fixed jpg->png->webp probe order).
            for (String otherExt : java.util.List.of(".jpg", ".png", ".webp")) {
                if (!otherExt.equals(ext)) {
                    graphSharePointService.deleteFileIfExists(basePath + "/Photo" + otherExt);
                }
            }

            String sharepointUrl = graphSharePointService
                    .uploadDocument(folder.locationTemplate(), folder.employeeFolder(), fixedFileName, bytes)
                    .orElse(null);
            profile.setPhotoSharepointUrl(sharepointUrl);
        } catch (Exception e) {
            log.warn("Échec du mirroring SharePoint de la photo pour profileId={}: {}",
                    profile.getId(), e.getMessage());
        }
    }

    /** pays.photo_sharepoint_location for this profile's pays, the employee's
     * resolved+normalized folder name, and the ambiguity guard — every step needed
     * before either mirroring an upload or self-healing a serve cache-miss (this
     * second use is added by a later task in this same file, not part of this task).
     * Returns null if SharePoint mirroring should be skipped for this profile, for
     * any reason (no location configured for this pays, no usable employee name, or
     * an ambiguous name). */
    private EmployeeSharepointFolder resolveEmployeeSharepointFolder(Long paysId, Long userId) {
        String locationTemplate = jdbcTemplate.queryForObject(
                "SELECT photo_sharepoint_location FROM [dbo].[pays] WHERE id = ?",
                String.class, paysId);
        if (locationTemplate == null || locationTemplate.isBlank()) return null;

        String fullName = jdbcTemplate.queryForObject(
                "SELECT fullName FROM [dbo].[Users] WHERE id = ?", String.class, userId);
        String employeeFolder = employeeFolderResolver.normalize(fullName);
        if (employeeFolder == null) return null;

        if (employeeFolderResolver.isAmbiguous(employeeFolder, paysId)) {
            log.warn("Plusieurs employés partagent le nom de dossier SharePoint '{}' — opération " +
                    "photo ignorée par sécurité", employeeFolder);
            return null;
        }

        return new EmployeeSharepointFolder(locationTemplate, employeeFolder);
    }

    private record EmployeeSharepointFolder(String locationTemplate, String employeeFolder) {}

    private EmployeeProfile findOrThrow(Long id) {
        return profileRepository.findById(id).orElseThrow(() ->
                new AppException(com.daf360.rh.exception.ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Profil introuvable: id=" + id));
    }

    private static final String USER_MATRICULE_SQL =
        "SELECT employee_id, fullName FROM [dbo].[Users] WHERE id = ?";

    private EmployeeProfileResponseDto toResponseDto(EmployeeProfile profile, Authentication auth) {
        EmployeeProfileResponseDto dto = mapper.toResponseDto(profile);
        // Enrich with matricule + fullName from Users table
        try {
            jdbcTemplate.queryForObject(USER_MATRICULE_SQL,
                (rs, n) -> {
                    dto.setMatricule(rs.getString("employee_id"));
                    dto.setFullName(rs.getString("fullName"));
                    return null;
                }, profile.getUserId());
        } catch (Exception ignored) {
            // If Users row not found, leave matricule/fullName null
        }
        // Set outside the try: the label lookup can fail (missing pays row) but the id is
        // already on the entity, and the page needs it to create contracts.
        dto.setPaysId(profile.getPaysId());
        try {
            String paysLabel = jdbcTemplate.queryForObject(
                    "SELECT french_label FROM [dbo].[pays] WHERE id = ?",
                    String.class, profile.getPaysId());
            dto.setPaysLabel(paysLabel);
        } catch (Exception ignored) {}
        if (profile.getRegimeTemplateId() != null) {
            regimeRepo.findById(profile.getRegimeTemplateId())
                    .ifPresent(r -> dto.setRegimeLabelFr(r.getLabelFr()));
        }
        if (!hasSensitiveAccess(auth)) {
            maskSensitiveFields(dto);
        }
        return dto;
    }

    private void maskSensitiveFields(EmployeeProfileResponseDto dto) {
        dto.setNationalId(null);
        dto.setPassportNumber(null);
        dto.setBankId(null);
        dto.setBankName(null);
        dto.setIban(null);
        dto.setBankAccountNumber(null);
        dto.setRib(null);
        dto.setSocialSecurityNumber(null);
        dto.setTaxId(null);
    }

    private boolean hasSensitiveAccess(Authentication auth) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> SENSITIVE_PERMISSIONS.contains(a.getAuthority()));
    }

    private void pseudonymise(EmployeeProfile profile) {
        String token = "ARCHIVED_" + profile.getId();
        profile.setPersonalEmail(null);
        profile.setPhone(null);
        profile.setPersonalAddress(null);
        profile.setDateOfBirth(null);
        profile.setGender(null);
        profile.setNationalId(null);
        profile.setPassportNumber(null);
        profile.setBank(null);
        profile.setIban(null);
        profile.setBankAccountNumber(null);
        profile.setRib(null);
        profile.setSocialSecurityNumber(null);
        profile.setTaxId(null);
        profile.setEmergencyContactName(null);
        profile.setEmergencyContactPhone(null);
        profile.setEmergencyContactRelation(null);
        jdbcTemplate.update(
                "UPDATE [dbo].[Users] SET fullName = ?, email = NULL WHERE id = ?",
                token, profile.getUserId());
        log.info("Pseudonymised profile id={}", profile.getId());
    }

    private String actorId(Authentication auth) {
        if (auth == null) return "SYSTEM";
        Object p = auth.getPrincipal();
        return p != null ? p.toString() : "SYSTEM";
    }

    private String safeJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    private Object[] buildArgs(String searchLike, Long paysId, String status,
                               Integer offset, Integer limit) {
        List<Object> args = new ArrayList<>();
        if (searchLike != null) { args.add(searchLike); args.add(searchLike); }
        if (paysId     != null) { args.add(paysId); }
        if (status     != null) { args.add(status); }
        if (offset     != null) { args.add(offset); args.add(limit); }
        return args.toArray();
    }
}
