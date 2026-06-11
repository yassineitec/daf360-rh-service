package com.daf360.rh.controller;

import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.dto.regime.RegimeAssignmentDto;
import com.daf360.rh.dto.regime.RegimeDetailDto;
import com.daf360.rh.dto.regime.RegimeHistoryItem;
import com.daf360.rh.dto.regime.EmployeeRegimeOverview;
import com.daf360.rh.dto.regime.RegimeOverviewStats;
import com.daf360.rh.dto.regime.RegimeRoleAssignmentResponse;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeResponseDto;
import com.daf360.rh.service.RegimeResolutionService;
import com.daf360.rh.service.WorkingTimeRegimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class WorkingTimeRegimeController {

    private final WorkingTimeRegimeService regimeService;
    private final RegimeResolutionService  resolutionService;

    // ── Regime templates ──────────────────────────────────────────────────────

    /**
     * GET /api/hr/regimes?paysId=1
     */
    @GetMapping("/api/hr/regimes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WorkingTimeRegimeResponseDto>> list(
            @RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.listByPays(paysId));
    }

    /**
     * GET /api/hr/regimes/{id}
     */
    @GetMapping("/api/hr/regimes/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkingTimeRegimeResponseDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getById(id));
    }

    /**
     * POST /api/hr/regimes
     * Create a regime template. Required: HR_MANAGER
     */
    @PostMapping("/api/hr/regimes")
    @PreAuthorize("hasAnyAuthority('HR_CREATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<WorkingTimeRegimeResponseDto> create(
            @Valid @RequestBody WorkingTimeRegimeCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regimeService.create(dto, auth));
    }

    /**
     * PATCH /api/hr/regimes/{id}
     * Update a regime template. Required: HR_MANAGER
     */
    @PatchMapping("/api/hr/regimes/{id}")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES', 'ADMIN_REGIMES')")
    public ResponseEntity<WorkingTimeRegimeResponseDto> update(
            @PathVariable Long id,
            @RequestBody WorkingTimeRegimeCreateDto dto,   // no @Valid — partial PATCH, paysId/code optional
            Authentication auth) {
        return ResponseEntity.ok(regimeService.update(id, dto, auth));
    }

    /**
     * DELETE /api/hr/regimes/{id}
     * Soft-deactivate a regime template. Required: HR_MANAGER
     */
    @DeleteMapping("/api/hr/regimes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('HR_ARCHIVE_PROFILE', 'HR_ADMIN_ROLES')")
    public void deactivate(@PathVariable Long id, Authentication auth) {
        regimeService.deactivate(id, auth);
    }

    // ── Profile assignment ────────────────────────────────────────────────────

    /**
     * POST /api/hr/profiles/{profileId}/regime
     * Assign a regime to an employee profile. Required: HR_MANAGER
     */
    @PostMapping("/api/hr/profiles/{profileId}/regime")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(
            @PathVariable Long profileId,
            @Valid @RequestBody RegimeAssignmentDto dto,
            Authentication auth) {
        regimeService.assignToProfile(profileId, dto, auth);
    }

    // ── Extended endpoints ────────────────────────────────────────────────────

    /**
     * GET /api/hr/regimes/{id}/detail
     */
    @GetMapping("/api/hr/regimes/{id}/detail")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeDetailDto> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getRegimeDetail(id));
    }

    /**
     * DELETE /api/hr/regimes/{id}/full — validated soft-delete
     */
    @DeleteMapping("/api/hr/regimes/{id}/full")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public void deleteRegimeFull(@PathVariable Long id, Authentication auth) {
        regimeService.deleteRegime(id, auth);
    }

    /**
     * GET /api/hr/regimes/role-assignments?paysId=
     */
    @GetMapping("/api/hr/regimes/role-assignments")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<List<RegimeRoleAssignmentResponse>> listRoleAssignments(
            @RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.listRoleAssignments(paysId));
    }

    /**
     * POST /api/hr/regimes/role-assignments
     */
    @PostMapping("/api/hr/regimes/role-assignments")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeRoleAssignmentResponse> assignToRole(
            @Valid @RequestBody AssignRegimeToRoleRequest dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regimeService.assignRegimeToRole(dto, auth));
    }

    /**
     * DELETE /api/hr/regimes/role-assignments/{id}
     */
    @DeleteMapping("/api/hr/regimes/role-assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public void removeRoleAssignment(@PathVariable Long id, Authentication auth) {
        regimeService.removeRoleAssignment(id, auth);
    }

    /**
     * GET /api/hr/profiles/{id}/regime — resolved regime for an employee
     */
    @GetMapping("/api/hr/profiles/{id}/regime")
    @PreAuthorize("hasPermission(null,'HR_UPDATE_PROFILE')")
    public ResponseEntity<ResolvedRegimeDto> getResolvedRegime(@PathVariable Long id) {
        ResolvedRegimeDto resolved = resolutionService.resolveForEmployee(id);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    /**
     * POST /api/hr/profiles/{id}/regime/override — set employee regime override
     */
    @PostMapping("/api/hr/profiles/{id}/regime/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public void assignOverride(@PathVariable Long id,
            @Valid @RequestBody AssignRegimeToEmployeeRequest dto, Authentication auth) {
        regimeService.assignRegimeToEmployee(id, dto, auth);
    }

    /**
     * DELETE /api/hr/profiles/{id}/regime/override — remove employee regime override
     */
    @DeleteMapping("/api/hr/profiles/{id}/regime/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public void removeOverride(@PathVariable Long id, Authentication auth) {
        regimeService.removeEmployeeOverride(id, auth);
    }

    /**
     * GET /api/hr/profiles/{id}/regime/history
     */
    @GetMapping("/api/hr/profiles/{id}/regime/history")
    @PreAuthorize("hasPermission(null,'HR_UPDATE_PROFILE')")
    public ResponseEntity<List<RegimeHistoryItem>> getRegimeHistory(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getEmployeeRegimeHistory(id));
    }

    /**
     * GET /api/hr/regimes/overview/stats?paysId=
     */
    @GetMapping("/api/hr/regimes/overview/stats")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeOverviewStats> getStats(@RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.getOverviewStats(paysId));
    }

    /**
     * GET /api/hr/regimes/overview/employees?paysId=
     */
    @GetMapping("/api/hr/regimes/overview/employees")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<List<EmployeeRegimeOverview>> getOverviewEmployees(@RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.getOverviewEmployees(paysId));
    }

    /**
     * GET /api/hr/regimes/resolve?employeeProfileId= OR ?roleId=&paysId=
     */
    @GetMapping("/api/hr/regimes/resolve")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public ResponseEntity<ResolvedRegimeDto> resolve(
            @RequestParam(required = false) Long employeeProfileId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Long paysId) {
        ResolvedRegimeDto result = null;
        if (employeeProfileId != null) {
            result = resolutionService.resolveForEmployee(employeeProfileId);
        } else if (roleId != null && paysId != null) {
            result = resolutionService.resolveForRole(roleId, paysId);
        }
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }
}
