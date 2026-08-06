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
import com.daf360.rh.dto.regime.WeeklyScheduleDto;
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

import java.time.LocalDate;
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
    // Read-only resolved regime. Relaxed to any authenticated user (matches the sibling
    // /api/hr/regimes reads) so the pointage module can show each employee their own
    // working hours/chronometer. Writes below keep the HR_UPDATE_PROFILE guard.
    @GetMapping("/api/hr/profiles/{id}/regime")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResolvedRegimeDto> getResolvedRegime(@PathVariable Long id) {
        ResolvedRegimeDto resolved = resolutionService.resolveForEmployee(id);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    /**
     * GET /api/hr/profiles/{id}/regime/weekly-schedule
     * Current week's expected schedule per day based on the resolved regime.
     *
     * 204 when no regime is configured — callers must show "not configured" rather than
     * a fabricated 5×8h week (the old stub made unconfigured entities look configured).
     */
    @GetMapping("/api/hr/profiles/{id}/regime/weekly-schedule")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WeeklyScheduleDto> getWeeklySchedule(@PathVariable Long id) {
        ResolvedRegimeDto regime = resolutionService.resolveForEmployee(id);
        if (regime == null) return ResponseEntity.noContent().build();
        // "This week" is the employee's week: derived in the resolved regime's zone, so a
        // Monday-morning caller in a +9 entity does not get last week from a UTC server.
        return ResponseEntity.ok(resolutionService.buildWeeklySchedule(regime, todayFor(regime)));
    }

    // ── By portal USER id ─────────────────────────────────────────────────────
    // employee_profiles.id and Users.id are different sequences. Every caller that
    // holds a userId (the whole pointage module: JWT subject, users-for-sync) must use
    // these, not the /profiles/{id}/ routes above — those resolve a DIFFERENT employee.

    /** GET /api/hr/users/{userId}/regime — resolved regime for a portal user. */
    @GetMapping("/api/hr/users/{userId}/regime")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResolvedRegimeDto> getResolvedRegimeForUser(@PathVariable Long userId) {
        ResolvedRegimeDto resolved = resolutionService.resolveForUser(userId);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    /** GET /api/hr/users/{userId}/regime/weekly-schedule — current week for a portal user. */
    @GetMapping("/api/hr/users/{userId}/regime/weekly-schedule")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WeeklyScheduleDto> getWeeklyScheduleForUser(@PathVariable Long userId) {
        ResolvedRegimeDto regime = resolutionService.resolveForUser(userId);
        if (regime == null) return ResponseEntity.noContent().build();
        // "This week" is the employee's week: derived in the resolved regime's zone, so a
        // Monday-morning caller in a +9 entity does not get last week from a UTC server.
        return ResponseEntity.ok(resolutionService.buildWeeklySchedule(regime, todayFor(regime)));
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

    /**
     * "Today" in the regime's own zone, for deriving which week to build.
     *
     * Falls back to the server date only when the entity has no timezone — in which case the
     * response's null `timezone` already tells the client the schedule is unconfigured.
     */
    private static LocalDate todayFor(ResolvedRegimeDto regime) {
        String tz = regime.getTimezone();
        if (tz == null || tz.isBlank()) return LocalDate.now();
        try {
            return LocalDate.now(java.time.ZoneId.of(tz));
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
