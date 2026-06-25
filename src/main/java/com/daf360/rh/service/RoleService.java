package com.daf360.rh.service;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.Role;
import com.daf360.rh.domain.RolePermission;
import com.daf360.rh.dto.admin.CreateRoleRequest;
import com.daf360.rh.dto.admin.PermissionCodeResponse;
import com.daf360.rh.dto.admin.PermissionGroupResponse;
import com.daf360.rh.dto.admin.RoleResponseDto;
import com.daf360.rh.dto.admin.RoleUserItem;
import com.daf360.rh.dto.admin.UpdatePermissionsDto;
import com.daf360.rh.dto.admin.UpdateRoleRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.RolePermissionRepository;
import com.daf360.rh.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin management of [Roles] and [RolePermissions].
 *
 * ⚠ CK_RolePermissions_Permission is a DB-level CHECK CONSTRAINT with exactly
 * 32 allowed values (verified 2026-05-31). Submitting any other value will throw
 * a DataIntegrityViolationException. ALLOWED_PERMISSIONS mirrors that constraint exactly.
 *
 * This service performs ASSIGNMENT management (which roles get which permissions),
 * NOT the creation of new permission types.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RoleService {

    /**
     * The allowed values for CK_RolePermissions_Permission.
     * Single source of truth: derived from PermissionCatalog.ALL_CODES so this
     * set stays in sync automatically when new permissions are added to the catalog.
     * Any new permission also requires a DB migration to extend the CHECK CONSTRAINT.
     */
    public static final Set<String> ALLOWED_PERMISSIONS = PermissionCatalog.ALL_CODES;

    private final RoleRepository           roleRepo;
    private final RolePermissionRepository permRepo;
    private final AuditService             auditService;
    private final JdbcTemplate             jdbc;

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoleResponseDto> listRoles() {
        List<Role> roles = roleRepo.findAll();

        // Load all permissions in one query (avoids N+1)
        Map<Long, List<String>> permsByRole = new HashMap<>();
        jdbc.query(
                "SELECT role_id, permission FROM RolePermissions ORDER BY role_id, permission",
                (RowCallbackHandler) rs -> {
                    Long roleId = rs.getLong("role_id");
                    String perm  = rs.getString("permission");
                    permsByRole.computeIfAbsent(roleId, k -> new ArrayList<>()).add(perm);
                });

        // Load all user counts in one query
        Map<Long, Integer> userCounts = new HashMap<>();
        jdbc.query(
                "SELECT role_id, COUNT(*) AS cnt FROM Users " +
                "WHERE (isActive = 1 OR isActive IS NULL) GROUP BY role_id",
                (RowCallbackHandler) rs ->
                        userCounts.put(rs.getLong("role_id"), rs.getInt("cnt")));

        // Build a quick name lookup for parent roles
        Map<Long, String> roleNames = roles.stream()
                .filter(r -> r.getId() != null && r.getFrenchName() != null)
                .collect(Collectors.toMap(Role::getId, Role::getFrenchName));

        return roles.stream().map(r -> {
            RoleResponseDto dto = new RoleResponseDto();
            dto.setId(r.getId());
            dto.setFrenchName(r.getFrenchName());
            dto.setParentRoleId(r.getParentRoleId());
            dto.setShowAll(r.getShowAll());
            List<String> perms = permsByRole.getOrDefault(r.getId(), List.of());
            dto.setPermissions(perms);
            dto.setPermissionCount(perms.size());
            if (r.getParentRoleId() != null) {
                dto.setParentRoleName(roleNames.get(r.getParentRoleId()));
            }
            dto.setUserCount(userCounts.getOrDefault(r.getId(), 0));
            return dto;
        }).toList();
    }

    @Transactional(readOnly = true)
    public RoleResponseDto getRole(Long id) {
        return roleRepo.findById(id).map(r -> {
            RoleResponseDto dto = new RoleResponseDto();
            dto.setId(r.getId());
            dto.setFrenchName(r.getFrenchName());
            dto.setParentRoleId(r.getParentRoleId());
            dto.setShowAll(r.getShowAll());
            dto.setPermissions(permRepo.findPermissionsByRoleId(r.getId()));
            return dto;
        }).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + id));
    }

    @Transactional(readOnly = true)
    public List<String> getPermissions(Long roleId) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }
        return permRepo.findPermissionsByRoleId(roleId);
    }

    // ── Update permissions ────────────────────────────────────────────────────

    /**
     * Full replacement of a role's permission set.
     * All submitted values are validated against ALLOWED_PERMISSIONS before any DB write.
     */
    public RoleResponseDto updatePermissions(Long roleId, UpdatePermissionsDto dto, Authentication auth) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }

        // Validate every permission against the DB constraint
        List<String> invalid = dto.getPermissions().stream()
                .filter(p -> !ALLOWED_PERMISSIONS.contains(p))
                .toList();
        if (!invalid.isEmpty()) {
            throw new AppException(ErrorCode.PERMISSION_NOT_ALLOWED,
                    "Permissions non autorisées: " + invalid
                    + ". Valeurs autorisées: " + ALLOWED_PERMISSIONS);
        }

        List<String> before = permRepo.findPermissionsByRoleId(roleId);

        // Use JdbcTemplate for both DELETE and INSERT — bypasses JPA cache entirely.
        // JPA save() + clearAutomatically had a flush-timing bug where INSERTs were
        // not visible to the subsequent native SELECT in getRole().
        jdbc.update("DELETE FROM RolePermissions WHERE role_id = ?", roleId);
        for (String p : dto.getPermissions()) {
            jdbc.update("INSERT INTO RolePermissions(role_id, permission) VALUES(?, ?)", roleId, p);
        }

        auditService.log(actorId(auth), "UPDATE_PERMISSIONS", "RolePermissions", roleId,
                String.join(",", before),
                String.join(",", dto.getPermissions()));

        return getRole(roleId);
    }

    // ── Role CRUD ─────────────────────────────────────────────────────────────

    public RoleResponseDto createRole(CreateRoleRequest dto, Authentication auth) {
        // Uniqueness check
        if (roleRepo.findByFrenchName(dto.getFrenchName()).isPresent()) {
            throw new AppException(ErrorCode.ALREADY_EXISTS, "Un rôle avec ce nom existe déjà");
        }

        Role role = Role.builder()
                .frenchName(dto.getFrenchName())
                .parentRoleId(dto.getParentRoleId())
                .showAll(dto.getShowAll() != null ? dto.getShowAll() : false)
                .createdAt(OffsetDateTime.now())
                .deleted(false)
                .build();
        Role saved = roleRepo.save(role);

        if (dto.getPermissions() != null && !dto.getPermissions().isEmpty()) {
            List<String> invalid = dto.getPermissions().stream()
                    .filter(p -> !ALLOWED_PERMISSIONS.contains(p))
                    .toList();
            if (!invalid.isEmpty()) {
                throw new AppException(ErrorCode.PERMISSION_NOT_ALLOWED,
                        "Permissions non autorisées: " + invalid);
            }
            dto.getPermissions().forEach(p ->
                    permRepo.save(new RolePermission(
                            new RolePermission.RolePermissionId(saved.getId(), p))));
        }

        auditService.log(actorId(auth), "CREATE_ROLE", "Role", saved.getId(), null, saved.getFrenchName());
        return getRole(saved.getId());
    }

    public RoleResponseDto updateRole(Long id, UpdateRoleRequest dto, Authentication auth) {
        Role role = roleRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + id));

        if (dto.getFrenchName() != null && !dto.getFrenchName().equals(role.getFrenchName())) {
            Integer userCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM [dbo].[Users] WHERE role_id = ? AND (isActive = 1 OR isActive IS NULL)",
                    Integer.class, id);
            int count = userCount != null ? userCount : 0;
            if (count > 0 && !Boolean.TRUE.equals(dto.getForceRename())) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Ce rôle est assigné à " + count + " utilisateurs. Confirmez avec forceRename=true.");
            }
            role.setFrenchName(dto.getFrenchName());
        }

        if (dto.getParentRoleId() != null) {
            role.setParentRoleId(dto.getParentRoleId() == 0L ? null : dto.getParentRoleId());
        }

        if (dto.getShowAll() != null) {
            role.setShowAll(dto.getShowAll());
        }

        role.setUpdatedAt(OffsetDateTime.now());
        roleRepo.save(role);

        auditService.log(actorId(auth), "UPDATE_ROLE", "Role", id, null, role.getFrenchName());
        return getRole(id);
    }

    public void deleteRole(Long id, Authentication auth) {
        Role role = roleRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + id));

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[Users] WHERE role_id = ? AND (isActive = 1 OR isActive IS NULL)",
                Integer.class, id);
        int count = userCount != null ? userCount : 0;
        if (count > 0) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Impossible de supprimer : " + count + " utilisateurs ont ce rôle. Réassignez-les d'abord.");
        }

        permRepo.deleteByRoleId(id);

        role.setDeleted(true);
        role.setDeletedAt(OffsetDateTime.now());
        roleRepo.save(role);

        auditService.log(actorId(auth), "DELETE_ROLE", "Role", id, role.getFrenchName(), null);
    }

    public void addPermission(Long roleId, String code, Authentication auth) {
        if (!ALLOWED_PERMISSIONS.contains(code)) {
            throw new AppException(ErrorCode.PERMISSION_NOT_ALLOWED,
                    "Permission non autorisée: " + code);
        }
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + roleId);
        }
        // Idempotent: skip if already assigned
        if (permRepo.findPermissionsByRoleId(roleId).contains(code)) {
            return;
        }
        permRepo.save(new RolePermission(new RolePermission.RolePermissionId(roleId, code)));
        auditService.log(actorId(auth), "ADD_PERMISSION", "Role", roleId, null, code);
    }

    public void removePermission(Long roleId, String code, Authentication auth) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + roleId);
        }
        permRepo.deleteByRoleIdAndPermission(roleId, code);
        auditService.log(actorId(auth), "REMOVE_PERMISSION", "Role", roleId, code, null);
    }

    @Transactional(readOnly = true)
    public List<PermissionGroupResponse> getPermissionCatalog() {
        return PermissionCatalog.GROUPS.stream()
                .map(group -> PermissionGroupResponse.builder()
                        .groupName(group.label())
                        .permissions(group.codes().stream()
                                .map(code -> PermissionCodeResponse.builder()
                                        .code(code)
                                        .label(code)
                                        .build())
                                .toList())
                        .build())
                .toList();
    }

    // ── User → Role management ────────────────────────────────────────────────

    private static final String LIST_ROLE_USERS_SQL =
        "SELECT u.id AS userId, u.fullName, u.username AS email, u.pays_id AS paysId, " +
        "       p.french_label AS paysLabel " +
        "FROM Users u " +
        "LEFT JOIN pays p ON p.id = u.pays_id " +
        "WHERE u.role_id = ? AND (u.isActive = 1 OR u.isActive IS NULL) " +
        "ORDER BY u.fullName";

    private static final String SEARCH_USERS_SQL =
        "SELECT TOP 30 u.id AS userId, u.fullName, u.username AS email, u.pays_id AS paysId, " +
        "       p.french_label AS paysLabel, r.frenchName AS currentRoleName " +
        "FROM Users u " +
        "LEFT JOIN pays  p ON p.id = u.pays_id " +
        "LEFT JOIN Roles r ON r.id = u.role_id " +
        "WHERE (u.isActive = 1 OR u.isActive IS NULL) " +
        "  AND (u.role_id IS NULL OR u.role_id <> ?) " +
        "  AND (u.fullName LIKE ? OR u.username LIKE ?) " +
        "ORDER BY u.fullName";

    @Transactional(readOnly = true)
    public List<RoleUserItem> listRoleUsers(Long roleId) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }
        return jdbc.query(LIST_ROLE_USERS_SQL,
                (rs, rowNum) -> RoleUserItem.builder()
                        .userId(rs.getLong("userId"))
                        .fullName(rs.getString("fullName"))
                        .email(rs.getString("email"))
                        .paysId(rs.getLong("paysId"))
                        .paysLabel(rs.getString("paysLabel"))
                        .build(),
                roleId);
    }

    @Transactional(readOnly = true)
    public List<RoleUserItem> searchUsersForRole(Long roleId, String q) {
        String pattern = "%" + (q == null ? "" : q.trim()) + "%";
        return jdbc.query(SEARCH_USERS_SQL,
                (rs, rowNum) -> RoleUserItem.builder()
                        .userId(rs.getLong("userId"))
                        .fullName(rs.getString("fullName"))
                        .email(rs.getString("email"))
                        .paysId(rs.getLong("paysId"))
                        .paysLabel(rs.getString("paysLabel"))
                        .currentRoleName(rs.getString("currentRoleName"))
                        .build(),
                roleId, pattern, pattern);
    }

    public void assignUserToRole(Long roleId, Long userId, Authentication auth) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }
        String before = jdbc.queryForObject(
                "SELECT ISNULL(CAST(role_id AS VARCHAR), 'null') FROM Users WHERE id = ?",
                String.class, userId);
        jdbc.update("UPDATE Users SET role_id = ? WHERE id = ?", roleId, userId);
        auditService.log(actorId(auth), "ASSIGN_USER_ROLE", "User", userId,
                "role_id=" + before, "role_id=" + roleId);
    }

    public void removeUserFromRole(Long roleId, Long userId, Authentication auth) {
        jdbc.update(
                "UPDATE Users SET role_id = NULL WHERE id = ? AND role_id = ?",
                userId, roleId);
        auditService.log(actorId(auth), "REMOVE_USER_ROLE", "User", userId,
                "role_id=" + roleId, "role_id=null");
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
