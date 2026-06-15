package com.daf360.rh.controller;

import com.daf360.rh.dto.admin.CreateRoleRequest;
import com.daf360.rh.dto.admin.RoleResponseDto;
import com.daf360.rh.dto.admin.RoleUserItem;
import com.daf360.rh.dto.admin.UpdatePermissionsDto;
import com.daf360.rh.dto.admin.UpdateRoleRequest;
import com.daf360.rh.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/hr/admin/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** GET /api/hr/admin/roles — list all roles with their current permissions */
    @GetMapping
    //@PreAuthorize("hasAnyAuthority('GET_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<List<RoleResponseDto>> list() {
        return ResponseEntity.ok(roleService.listRoles());
    }

    /** GET /api/hr/admin/roles/{id} */
    @GetMapping("/{id}")
    //@PreAuthorize("hasAnyAuthority('GET_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RoleResponseDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.getRole(id));
    }

    /** GET /api/hr/admin/roles/{id}/permissions */
    @GetMapping("/{id}/permissions")
    //@PreAuthorize("hasAnyAuthority('GET_PERMISSIONS', 'GET_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<List<String>> permissions(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.getPermissions(id));
    }

    /**
     * PATCH /api/hr/admin/roles/{id}/permissions
     * Full replacement of role's permissions.
     * All values must be in the ALLOWED_PERMISSIONS whitelist.
     */
    @PatchMapping("/{id}/permissions")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RoleResponseDto> updatePermissions(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePermissionsDto dto,
            Authentication auth) {
        return ResponseEntity.ok(roleService.updatePermissions(id, dto, auth));
    }

    /** GET /api/hr/admin/roles/allowed-permissions — returns the full allowed set */
    @GetMapping("/allowed-permissions")
    //@PreAuthorize("hasAnyAuthority('GET_PERMISSIONS', 'GET_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<Set<String>> allowedPermissions() {
        return ResponseEntity.ok(RoleService.ALLOWED_PERMISSIONS);
    }

    /** POST /api/hr/admin/roles — create new role */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RoleResponseDto> create(@RequestBody CreateRoleRequest dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleService.createRole(dto, auth));
    }

    /** PATCH /api/hr/admin/roles/{id} — update role info (not permissions) */
    @PatchMapping("/{id}")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RoleResponseDto> update(@PathVariable Long id, @RequestBody UpdateRoleRequest dto, Authentication auth) {
        return ResponseEntity.ok(roleService.updateRole(id, dto, auth));
    }

    /** DELETE /api/hr/admin/roles/{id} — delete role (only if no users assigned) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public void delete(@PathVariable Long id, Authentication auth) {
        roleService.deleteRole(id, auth);
    }

    /** PUT /api/hr/admin/roles/{id}/permissions — full replacement (same as existing PATCH) */
    @PutMapping("/{id}/permissions")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RoleResponseDto> replacePermissions(@PathVariable Long id, @Valid @RequestBody UpdatePermissionsDto dto, Authentication auth) {
        return ResponseEntity.ok(roleService.updatePermissions(id, dto, auth));
    }

    /** POST /api/hr/admin/roles/{id}/permissions/{code} — add one permission */
    @PostMapping("/{id}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public void addPermission(@PathVariable Long id, @PathVariable String code, Authentication auth) {
        roleService.addPermission(id, code, auth);
    }

    /** DELETE /api/hr/admin/roles/{id}/permissions/{code} — remove one permission */
    @DeleteMapping("/{id}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public void removePermission(@PathVariable Long id, @PathVariable String code, Authentication auth) {
        roleService.removePermission(id, code, auth);
    }

    // ── User management ───────────────────────────────────────────────────────

    /** GET /api/hr/admin/roles/{id}/users — list users assigned to this role */
    @GetMapping("/{id}/users")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<List<RoleUserItem>> listUsers(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.listRoleUsers(id));
    }

    /** GET /api/hr/admin/roles/{id}/users/search?q=X — search users not in this role */
    @GetMapping("/{id}/users/search")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<List<RoleUserItem>> searchUsers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(roleService.searchUsersForRole(id, q));
    }

    /** POST /api/hr/admin/roles/{id}/users/{userId} — assign user to role */
    @PostMapping("/{id}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public void assignUser(@PathVariable Long id, @PathVariable Long userId, Authentication auth) {
        roleService.assignUserToRole(id, userId, auth);
    }

    /** DELETE /api/hr/admin/roles/{id}/users/{userId} — remove user from role */
    @DeleteMapping("/{id}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES')")
    public void removeUser(@PathVariable Long id, @PathVariable Long userId, Authentication auth) {
        roleService.removeUserFromRole(id, userId, auth);
    }
}
