package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.profile.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.mapper.EmployeeProfileMapper;
import com.daf360.rh.repository.*;
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
    private final LeaveBalanceService       leaveBalanceService;
    private final AppProperties             appProperties;

    // ── Dimension repos injected for FK lookups ───────────────────────────────
    private final GradeRepository        gradeRepo;
    private final DisciplineRepository   disciplineRepo;
    private final NogLevelRepository     nogLevelRepo;
    private final HrDepartmentRepository departmentRepo;
    private final BankRepository         bankRepo;
    private final NationalityRepository  nationalityRepo;

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
        return profileRepository.search(
                filter.getPaysId(),
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
        }

        String before = safeJson(profile);

        // Apply non-dimension scalar fields via MapStruct (PATCH semantics)
        mapper.updateEntityFromDto(dto, profile);

        // Resolve and apply dimension FK fields explicitly
        if (dto.getNationalityId() != null) {
            nationalityRepo.findById(dto.getNationalityId()).ifPresent(profile::setNationality);
        }
        if (dto.getGradeId() != null) {
            gradeRepo.findById(dto.getGradeId()).ifPresent(profile::setGrade);
        }
        if (dto.getDisciplineId() != null) {
            disciplineRepo.findById(dto.getDisciplineId()).ifPresent(profile::setDiscipline);
        }
        if (dto.getNogLevelId() != null) {
            nogLevelRepo.findById(dto.getNogLevelId()).ifPresent(profile::setNogLevel);
        }
        if (dto.getDepartmentId() != null) {
            departmentRepo.findById(dto.getDepartmentId()).ifPresent(profile::setDepartment);
        }
        if (dto.getBankId() != null) {
            bankRepo.findById(dto.getBankId()).ifPresent(profile::setBank);
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

        if (next == LifecycleStatus.ACTIVE && current == LifecycleStatus.PRE_ONBOARDING) {
            int currentYear = java.time.Year.now(PARIS).getValue();
            leaveBalanceService.initializeBalances(id, currentYear);
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

    // ── Archive (soft delete + pseudonymise) ─────────────────────────────────

    public void archiveProfile(Long id, Authentication auth) {
        LifecycleTransitionDto dto = new LifecycleTransitionDto();
        dto.setNewStatus(LifecycleStatus.ARCHIVED);
        dto.setReason("Archive via DELETE endpoint");
        transitionLifecycle(id, dto, auth);
    }

    // ── Employee list (Users LEFT JOIN employee_profiles) ────────────────────
    // NOTE: V23 dimension tables (departments, grades, disciplines, nog_levels)
    // are not joined here because they may not exist yet in the target DB.
    // department / grade labels will be null until those tables are migrated.

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<EmployeeListItemDto> listAllEmployees(
            ProfileFilterDto filter, Pageable pageable) {

        String search     = (filter.getSearch() != null && !filter.getSearch().isBlank())
                            ? filter.getSearch().trim() : null;
        String searchLike = search != null ? "%" + search + "%" : null;
        Long   paysId     = filter.getPaysId();
        String status     = (filter.getStatus() != null && !filter.getStatus().isBlank())
                            ? filter.getStatus() : null;
        // TODO: add department / grade label filters once V23 tables are migrated

        int offset   = (int) pageable.getOffset();
        int pageSize = pageable.getPageSize();

        String baseFrom  =
            "FROM [dbo].[Users] u " +
            "LEFT JOIN [dbo].[pays] p ON p.id = u.pays_id " +
            "LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id AND (r.deleted = 0 OR r.deleted IS NULL) " +
            "LEFT JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id AND ep.deleted = 0 ";

        String baseWhere =
            "WHERE (u.isActive = 1 OR u.isActive IS NULL) " +
            (searchLike != null ? "AND (u.fullName LIKE ? OR u.username LIKE ?) " : "") +
            (paysId     != null ? "AND u.pays_id = ? " : "") +
            (status     != null ? "AND ep.lifecycle_status = ? " : "");

        List<Object> args = new ArrayList<>();
        if (searchLike != null) { args.add(searchLike); args.add(searchLike); }
        if (paysId     != null) { args.add(paysId); }
        if (status     != null) { args.add(status); }

        String listSql =
            "SELECT ep.id AS profile_id, u.id AS user_id, u.fullName AS full_name, " +
            "COALESCE(u.email, u.username) AS email, u.employee_id AS employee_id, u.pays_id AS pays_id, " +
            "p.french_label AS pays_label, u.role_id AS role_id, r.frenchName AS role_name, " +
            "ep.lifecycle_status AS lifecycle_status, ep.contract_type AS contract_type, " +
            "ep.hire_date AS hire_date, ep.photo_url AS photo_url, ep.gender AS gender " +
            baseFrom + baseWhere +
            "ORDER BY u.fullName " +
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
        List<String> paysList = jdbcTemplate.queryForList(
            "SELECT DISTINCT p.french_label " +
            "FROM [dbo].[pays] p " +
            "JOIN [dbo].[Users] u ON u.pays_id = p.id " +
            "WHERE (u.isActive = 1 OR u.isActive IS NULL) " +
            "  AND p.french_label IS NOT NULL " +
            "ORDER BY p.french_label",
            String.class);
        List<String> departmentList = jdbcTemplate.queryForList(
            "SELECT DISTINCT d.label_fr " +
            "FROM [dbo].[departments] d " +
            "WHERE d.is_active = 1 AND d.label_fr IS NOT NULL " +
            "ORDER BY d.label_fr",
            String.class);
        List<String> gradeList = jdbcTemplate.queryForList(
            "SELECT DISTINCT g.label_fr " +
            "FROM [dbo].[grades] g " +
            "WHERE g.is_active = 1 AND g.label_fr IS NOT NULL " +
            "ORDER BY g.label_fr",
            String.class);
        return new com.daf360.rh.dto.profile.FilterOptionsDto(
            departmentList, gradeList, paysList);
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
            updates.add("roleId = ?");
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
            // Build storage path
            String ext = contentType.contains("png") ? ".png"
                       : contentType.contains("webp") ? ".webp" : ".jpg";
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());
            java.nio.file.Files.createDirectories(dir);
            String filename = java.util.UUID.randomUUID() + ext;
            java.nio.file.Path target = dir.resolve(filename);
            java.nio.file.Files.copy(file.getInputStream(), target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Store the API path as photo_url (frontend will prefix with hrApiUrl)
            profile.setPhotoUrl("/api/hr/profiles/" + profileId + "/photo");
            profile.setUpdatedAt(java.time.OffsetDateTime.now());
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
     * Serve the profile photo as bytes.
     * Returns the LATEST file in the profiles/{profileId}/ directory.
     */
    @Transactional(readOnly = true)
    public byte[] servePhoto(Long profileId) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(
                    appProperties.getStoragePath(), "profiles", profileId.toString());
            if (!java.nio.file.Files.exists(dir)) return null;
            // Find latest file in directory
            java.util.Optional<java.nio.file.Path> latest = java.nio.file.Files.list(dir)
                    .filter(p -> !java.nio.file.Files.isDirectory(p))
                    .max(java.util.Comparator.comparingLong(p -> {
                        try { return java.nio.file.Files.getLastModifiedTime(p).toMillis(); }
                        catch (java.io.IOException e) { return 0L; }
                    }));
            return latest.isPresent() ? java.nio.file.Files.readAllBytes(latest.get()) : null;
        } catch (java.io.IOException e) {
            log.warn("Cannot serve photo for profile {}: {}", profileId, e.getMessage());
            return null;
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
        profile.setHomeAddress(null);
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
