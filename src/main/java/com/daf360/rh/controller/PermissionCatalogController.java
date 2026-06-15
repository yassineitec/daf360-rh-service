package com.daf360.rh.controller;

import com.daf360.rh.dto.admin.PermissionGroupResponse;
import com.daf360.rh.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/hr/admin/permissions")
@RequiredArgsConstructor
public class PermissionCatalogController {

    private final RoleService roleService;

    @GetMapping("/catalog")
    //@PreAuthorize("hasAnyAuthority('ADMIN_ROLES', 'HR_ADMIN_ROLES', 'GET_PERMISSIONS')")
    public ResponseEntity<List<PermissionGroupResponse>> getCatalog() {
        return ResponseEntity.ok(roleService.getPermissionCatalog());
    }
}
