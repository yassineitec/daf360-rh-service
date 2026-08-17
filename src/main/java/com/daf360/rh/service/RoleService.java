package com.daf360.rh.service;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.PaysScopeMode;
import com.daf360.rh.domain.Role;
import com.daf360.rh.dto.admin.CreateRoleRequest;
import com.daf360.rh.dto.admin.PermissionCodeResponse;
import com.daf360.rh.dto.admin.PermissionGroupResponse;
import com.daf360.rh.dto.admin.RoleResponseDto;
import com.daf360.rh.dto.admin.RoleUserItem;
import com.daf360.rh.dto.admin.UpdatePermissionsDto;
import com.daf360.rh.dto.admin.UpdateRoleRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.RolePaysScopeRepository;
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
    private final RolePaysScopeRepository  paysScopeRepo;
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

        // Country scope, also batched (same N+1 reasoning as the permissions above)
        Map<Long, List<Long>> scopeByRole = new HashMap<>();
        jdbc.query(
                "SELECT role_id, pays_id FROM RolePaysScope ORDER BY role_id, pays_id",
                (RowCallbackHandler) rs ->
                        scopeByRole.computeIfAbsent(rs.getLong("role_id"), k -> new ArrayList<>())
                                   .add(rs.getLong("pays_id")));

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
            dto.setPaysScopeMode(effectiveMode(r).name());
            dto.setPaysScope(scopeByRole.getOrDefault(r.getId(), List.of()));
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
            dto.setPaysScopeMode(effectiveMode(r).name());
            dto.setPaysScope(paysScopeRepo.findPaysIdsByRoleId(r.getId()));
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

        // This service owns ONLY the RH permission catalog. Any submitted code
        // outside it belongs to another module (e.g. finance FACT_*): the roles-admin
        // UI seeds its checkbox set from the role's FULL permission list, so foreign
        // codes get re-sent here. We must neither reject them (was a 400) nor wipe
        // them — RH manages its own codes and leaves the rest untouched.
        List<String> submittedRh = dto.getPermissions().stream()
                .filter(ALLOWED_PERMISSIONS::contains)
                .distinct()
                .toList();

        List<String> before = permRepo.findPermissionsByRoleId(roleId);

        // Use JdbcTemplate for both DELETE and INSERT — bypasses JPA cache entirely.
        // JPA save() + clearAutomatically had a flush-timing bug where INSERTs were
        // not visible to the subsequent native SELECT in getRole().
        // Delete ONLY this role's RH-catalog rows so foreign (other-module) grants survive.
        String placeholders = ALLOWED_PERMISSIONS.stream().map(c -> "?").collect(Collectors.joining(","));
        Object[] delArgs = new Object[ALLOWED_PERMISSIONS.size() + 1];
        delArgs[0] = roleId;
        int idx = 1;
        for (String c : ALLOWED_PERMISSIONS) delArgs[idx++] = c;
        jdbc.update("DELETE FROM RolePermissions WHERE role_id = ? AND permission IN (" + placeholders + ")", delArgs);

        for (String p : submittedRh) {
            jdbc.update("INSERT INTO RolePermissions(role_id, permission) VALUES(?, ?)", roleId, p);
        }

        auditService.log(actorId(auth), "UPDATE_PERMISSIONS", "RolePermissions", roleId,
                String.join(",", before),
                String.join(",", submittedRh));

        return getRole(roleId);
    }

    // ── Role CRUD ─────────────────────────────────────────────────────────────

    public RoleResponseDto createRole(CreateRoleRequest dto, Authentication auth) {
        // Uniqueness check
        if (roleRepo.findByFrenchName(dto.getFrenchName()).isPresent()) {
            throw new AppException(ErrorCode.ALREADY_EXISTS, "Un rôle avec ce nom existe déjà");
        }

        // showAll and paysScopeMode are two views of the same decision; whichever the caller
        // sent, both are stored consistently so the older readers of showAll stay correct.
        PaysScopeMode mode = dto.getPaysScopeMode() != null
                ? PaysScopeMode.from(dto.getPaysScopeMode())
                : (Boolean.TRUE.equals(dto.getShowAll()) ? PaysScopeMode.ALL : PaysScopeMode.OWN);

        Role role = Role.builder()
                .frenchName(dto.getFrenchName())
                .parentRoleId(dto.getParentRoleId())
                .showAll(mode == PaysScopeMode.ALL)
                .paysScopeMode(mode)
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
                    permRepo.insertPermission(saved.getId(), p));
        }

        writePaysScope(saved.getId(), dto.getPaysScope(), mode);

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

        // showAll and paysScopeMode must never disagree. Either field may arrive alone (the
        // existing UI only knows showAll), so whichever is present drives the other.
        if (dto.getPaysScopeMode() != null) {
            PaysScopeMode mode = PaysScopeMode.from(dto.getPaysScopeMode());
            role.setPaysScopeMode(mode);
            role.setShowAll(mode == PaysScopeMode.ALL);
        } else if (dto.getShowAll() != null) {
            role.setShowAll(dto.getShowAll());
            // Leaving LIST intact on showAll=false would silently keep a country list active;
            // dropping to OWN would silently discard a deliberate LIST. Preserve LIST only if
            // that is what the role already was.
            if (Boolean.TRUE.equals(dto.getShowAll())) {
                role.setPaysScopeMode(PaysScopeMode.ALL);
            } else if (effectiveMode(role) == PaysScopeMode.ALL) {
                role.setPaysScopeMode(PaysScopeMode.OWN);
            }
        }

        role.setUpdatedAt(OffsetDateTime.now());
        roleRepo.save(role);

        // null means "field absent from the PATCH" — leave the list alone. An empty list is a
        // deliberate clear, so it must go through.
        if (dto.getPaysScope() != null) {
            List<Long> before = paysScopeRepo.findPaysIdsByRoleId(id);
            writePaysScope(id, dto.getPaysScope(), effectiveMode(role));
            auditScopeChange(auth, id, before, paysScopeRepo.findPaysIdsByRoleId(id));
        }

        auditService.log(actorId(auth), "UPDATE_ROLE", "Role", id, null, role.getFrenchName());
        return getRole(id);
    }

    // ── Country (pays) scope ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Long> getPaysScope(Long roleId) {
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + roleId);
        }
        return paysScopeRepo.findPaysIdsByRoleId(roleId);
    }

    /** Full replacement of a role's country list. An empty/null list clears it. */
    public RoleResponseDto replacePaysScope(Long roleId, List<Long> paysIds, Authentication auth) {
        Role role = roleRepo.findById(roleId)
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + roleId));
        List<Long> before = paysScopeRepo.findPaysIdsByRoleId(roleId);
        writePaysScope(roleId, paysIds, effectiveMode(role));
        auditScopeChange(auth, roleId, before, paysScopeRepo.findPaysIdsByRoleId(roleId));
        return getRole(roleId);
    }

    /**
     * The role's mode, tolerating rows written before V74's backfill (null) and any legacy
     * showAll that was set without a mode.
     */
    private PaysScopeMode effectiveMode(Role role) {
        if (role.getPaysScopeMode() != null) return role.getPaysScopeMode();
        return Boolean.TRUE.equals(role.getShowAll()) ? PaysScopeMode.ALL : PaysScopeMode.OWN;
    }

    /**
     * Delete-then-insert, like updatePermissions. Unlike permissions there is no
     * cross-module concern here — RolePaysScope belongs to RH alone — so the whole role's
     * list is replaced rather than a filtered subset.
     *
     * Every id is validated against [pays] first: an unknown pays_id would be invisible
     * dead data that silently narrows the scope, and there is no FK to catch it (see V74).
     *
     * Rejects a non-empty list in OWN mode rather than accepting it silently. That mistake —
     * listing {TN, EG} on a role shared by Tunisian and Egyptian holders, expecting each to
     * stay in their own country — is precisely the one that would leak data if the list were
     * ever honoured, so it fails loudly at the point of entry instead.
     */
    private void writePaysScope(Long roleId, List<Long> paysIds, PaysScopeMode mode) {
        List<Long> requested = paysIds == null ? List.of()
                : paysIds.stream().filter(java.util.Objects::nonNull).distinct().toList();

        if (!requested.isEmpty() && mode != PaysScopeMode.LIST) {
            throw new AppException(ErrorCode.PAYS_SCOPE_INVALID,
                    "Une liste de pays n'a de sens qu'en mode LIST (mode actuel: " + mode
                    + "). En mode OWN chaque utilisateur voit uniquement son propre pays ; "
                    + "en mode ALL il voit tous les pays.");
        }

        if (!requested.isEmpty()) {
            String placeholders = requested.stream().map(p -> "?").collect(Collectors.joining(","));
            List<Long> known = jdbc.queryForList(
                    "SELECT id FROM [dbo].[pays] WHERE id IN (" + placeholders + ")",
                    Long.class, requested.toArray());
            List<Long> unknown = requested.stream().filter(p -> !known.contains(p)).toList();
            if (!unknown.isEmpty()) {
                throw new AppException(ErrorCode.PAYS_SCOPE_INVALID,
                        "Pays inconnu(s): " + unknown);
            }
        }

        paysScopeRepo.deleteByRoleId(roleId);
        for (Long paysId : requested) {
            paysScopeRepo.insertPaysId(roleId, paysId);
        }
    }

    private void auditScopeChange(Authentication auth, Long roleId,
                                  List<Long> before, List<Long> after) {
        auditService.log(actorId(auth), "UPDATE_ROLE_PAYS_SCOPE", "RolePaysScope", roleId,
                before.stream().map(String::valueOf).collect(Collectors.joining(",")),
                after.stream().map(String::valueOf).collect(Collectors.joining(",")));
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
        // RolePaysScope has no FK to Roles (see V74), so nothing cascades — clear it here or
        // the rows outlive the role and get inherited by whatever reuses the id.
        paysScopeRepo.deleteByRoleId(id);

        role.setDeleted(true);
        role.setDeletedAt(OffsetDateTime.now());
        roleRepo.save(role);

        auditService.log(actorId(auth), "DELETE_ROLE", "Role", id, role.getFrenchName(), null);
    }

    public void addPermission(Long roleId, String code, Authentication auth) {
        // Cross-module store: RH holds RolePermissions for every module. Other modules
        // (finance FACT_*, pointage POINTAGE_*) own their own catalogs and validate codes
        // before proxying to this endpoint, so we accept any non-blank code here rather
        // than restricting to RH's own catalog. (The enum CHECK constraint was dropped to
        // allow this; RH's own roles-admin only ever submits catalogued codes.)
        if (code == null || code.isBlank()) {
            throw new AppException(ErrorCode.PERMISSION_NOT_ALLOWED, "Code de permission vide");
        }
        if (!roleRepo.existsById(roleId)) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND, "Rôle introuvable: id=" + roleId);
        }
        // Idempotent: skip if already assigned
        if (permRepo.findPermissionsByRoleId(roleId).contains(code)) {
            return;
        }
        permRepo.insertPermission(roleId, code);
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
