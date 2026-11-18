package com.daf360.rh.controller;

import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.dto.regime.EmployeeRegimeAssignmentDto;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Régimes horaires: catalogue, affectations (rôle / employé) and resolved reads.
 *
 * Resolved reads take an optional {@code ?date=YYYY-MM-DD} (the employee's calendar date):
 * without it they answer for today in the entity's zone, as before.
 */
@RestController
@RequiredArgsConstructor
public class WorkingTimeRegimeController {

    private final WorkingTimeRegimeService regimeService;
    private final RegimeResolutionService  resolutionService;

    // ── Regime templates ──────────────────────────────────────────────────────

    /** GET /api/hr/regimes?paysId=1 — the catalogue (pickers in onboarding / admin). */
    @GetMapping("/api/hr/regimes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<WorkingTimeRegimeResponseDto>> list(
            @RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.listByPays(paysId));
    }

    /** GET /api/hr/regimes/{id} */
    @GetMapping("/api/hr/regimes/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkingTimeRegimeResponseDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getById(id));
    }

    /** POST /api/hr/regimes — ADMIN_REGIMES added: the Régimes admin screen creates with it. */
    @PostMapping("/api/hr/regimes")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'HR_CREATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<WorkingTimeRegimeResponseDto> create(
            @Valid @RequestBody WorkingTimeRegimeCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regimeService.create(dto, auth));
    }

    /** PATCH /api/hr/regimes/{id} */
    @PatchMapping("/api/hr/regimes/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<WorkingTimeRegimeResponseDto> update(
            @PathVariable Long id,
            @RequestBody WorkingTimeRegimeCreateDto dto,   // no @Valid — partial PATCH, paysId/code optional
            Authentication auth) {
        return ResponseEntity.ok(regimeService.update(id, dto, auth));
    }

    /**
     * DELETE /api/hr/regimes/{id} — legacy route, now the SAME validated delete as /full
     * (it used to deactivate a regime still in use, or the default, without any check).
     */
    @DeleteMapping("/api/hr/regimes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'HR_ARCHIVE_PROFILE', 'HR_ADMIN_ROLES')")
    public void deactivate(@PathVariable Long id, Authentication auth) {
        regimeService.deactivate(id, auth);
    }

    // ── Profile assignment (legacy route) ─────────────────────────────────────

    /**
     * POST /api/hr/profiles/{profileId}/regime — legacy. Now goes through the same checks
     * and history as /regime/override (it used to skip both).
     */
    @PostMapping("/api/hr/profiles/{profileId}/regime")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(
            @PathVariable Long profileId,
            @Valid @RequestBody RegimeAssignmentDto dto,
            Authentication auth) {
        regimeService.assignToProfile(profileId, dto, auth);
    }

    // ── Extended endpoints ────────────────────────────────────────────────────

    @GetMapping("/api/hr/regimes/{id}/detail")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeDetailDto> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getRegimeDetail(id));
    }

    /** DELETE /api/hr/regimes/{id}/full — validated soft-delete */
    @DeleteMapping("/api/hr/regimes/{id}/full")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public void deleteRegimeFull(@PathVariable Long id, Authentication auth) {
        regimeService.deleteRegime(id, auth);
    }

    /** GET /api/hr/regimes/role-assignments?paysId= — in force today or scheduled. */
    @GetMapping("/api/hr/regimes/role-assignments")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<List<RegimeRoleAssignmentResponse>> listRoleAssignments(
            @RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.listRoleAssignments(paysId));
    }

    @PostMapping("/api/hr/regimes/role-assignments")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeRoleAssignmentResponse> assignToRole(
            @Valid @RequestBody AssignRegimeToRoleRequest dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(regimeService.assignRegimeToRole(dto, auth));
    }

    @DeleteMapping("/api/hr/regimes/role-assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public void removeRoleAssignment(@PathVariable Long id, Authentication auth) {
        regimeService.removeRoleAssignment(id, auth);
    }

    // ── Resolved reads — self, or HR over the employee's entity ───────────────

    /** GET /api/hr/profiles/{id}/regime[?date=] — resolved regime for an employee profile. */
    @GetMapping("/api/hr/profiles/{id}/regime")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResolvedRegimeDto> getResolvedRegime(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        regimeService.assertCanReadProfileRegime(id, auth);
        ResolvedRegimeDto resolved = resolutionService.resolveForEmployee(id, date);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    /**
     * GET /api/hr/profiles/{id}/regime/weekly-schedule[?date=] — the week containing {@code date}
     * (default: this week). 204 when no regime is configured.
     */
    @GetMapping("/api/hr/profiles/{id}/regime/weekly-schedule")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WeeklyScheduleDto> getWeeklySchedule(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        regimeService.assertCanReadProfileRegime(id, auth);
        ResolvedRegimeDto regime = resolutionService.resolveForEmployee(id, date);
        if (regime == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolutionService.buildWeeklySchedule(regime, regime.getResolvedForDate()));
    }

    // ── By portal USER id ─────────────────────────────────────────────────────
    // employee_profiles.id and Users.id are different sequences. Every caller that
    // holds a userId (the whole pointage module: JWT subject, users-for-sync) must use
    // these, not the /profiles/{id}/ routes above — those resolve a DIFFERENT employee.

    /** GET /api/hr/users/{userId}/regime[?date=] — resolved regime for a portal user. */
    @GetMapping("/api/hr/users/{userId}/regime")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResolvedRegimeDto> getResolvedRegimeForUser(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        regimeService.assertCanReadUserRegime(userId, auth);
        ResolvedRegimeDto resolved = resolutionService.resolveForUser(userId, date);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    /** GET /api/hr/users/{userId}/regime/weekly-schedule[?date=] */
    @GetMapping("/api/hr/users/{userId}/regime/weekly-schedule")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<WeeklyScheduleDto> getWeeklyScheduleForUser(
            @PathVariable Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        regimeService.assertCanReadUserRegime(userId, auth);
        ResolvedRegimeDto regime = resolutionService.resolveForUser(userId, date);
        if (regime == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolutionService.buildWeeklySchedule(regime, regime.getResolvedForDate()));
    }

    // ── Personal assignments ──────────────────────────────────────────────────

    /** GET /api/hr/profiles/{id}/regime/assignments — personal assignments in force or scheduled. */
    @GetMapping("/api/hr/profiles/{id}/regime/assignments")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public ResponseEntity<List<EmployeeRegimeAssignmentDto>> listAssignments(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.listEmployeeAssignments(id));
    }

    /** DELETE /api/hr/profiles/{id}/regime/assignments/{assignmentId} — end or cancel ONE assignment. */
    @DeleteMapping("/api/hr/profiles/{id}/regime/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public void endAssignment(@PathVariable Long id, @PathVariable Long assignmentId, Authentication auth) {
        regimeService.removeEmployeeAssignment(id, assignmentId, auth);
    }

    /**
     * POST /api/hr/profiles/{id}/regime/override — add a personal assignment. Open-ended
     * replaces the employee's personal regime from its start date; bounded (a trip, a
     * temporary schedule) is laid on top and the previous one resumes after it.
     */
    @PostMapping("/api/hr/profiles/{id}/regime/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public void assignOverride(@PathVariable Long id,
            @Valid @RequestBody AssignRegimeToEmployeeRequest dto, Authentication auth) {
        regimeService.assignRegimeToEmployee(id, dto, auth);
    }

    /** DELETE /api/hr/profiles/{id}/regime/override — end every current and upcoming personal assignment. */
    @DeleteMapping("/api/hr/profiles/{id}/regime/override")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public void removeOverride(@PathVariable Long id, Authentication auth) {
        regimeService.removeEmployeeOverride(id, auth);
    }

    @GetMapping("/api/hr/profiles/{id}/regime/history")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public ResponseEntity<List<RegimeHistoryItem>> getRegimeHistory(@PathVariable Long id) {
        return ResponseEntity.ok(regimeService.getEmployeeRegimeHistory(id));
    }

    @GetMapping("/api/hr/regimes/overview/stats")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<RegimeOverviewStats> getStats(@RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.getOverviewStats(paysId));
    }

    @GetMapping("/api/hr/regimes/overview/employees")
    @PreAuthorize("hasPermission(null,'ADMIN_REGIMES')")
    public ResponseEntity<List<EmployeeRegimeOverview>> getOverviewEmployees(@RequestParam Long paysId) {
        return ResponseEntity.ok(regimeService.getOverviewEmployees(paysId));
    }

    /**
     * GET /api/hr/regimes/resolve?employeeProfileId=[&date=] OR ?roleId=&paysId=
     */
    @GetMapping("/api/hr/regimes/resolve")
    @PreAuthorize("hasAnyAuthority('ADMIN_REGIMES','HR_UPDATE_PROFILE')")
    public ResponseEntity<ResolvedRegimeDto> resolve(
            @RequestParam(required = false) Long employeeProfileId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(required = false) Long paysId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        ResolvedRegimeDto result = null;
        if (employeeProfileId != null) {
            regimeService.assertCanReadProfileRegime(employeeProfileId, auth);
            result = resolutionService.resolveForEmployee(employeeProfileId, date);
        } else if (roleId != null && paysId != null) {
            result = resolutionService.resolveForRole(roleId, paysId);
        }
        if (result == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(result);
    }
}
