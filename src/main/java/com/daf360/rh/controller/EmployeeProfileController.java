package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.dto.profile.*;
import com.daf360.rh.service.EmployeeProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.daf360.rh.dto.absence.LeaveBalanceDto;
import com.daf360.rh.dto.profile.FilterOptionsDto;
import com.daf360.rh.service.AbsenceService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/hr/profiles")
@RequiredArgsConstructor
public class EmployeeProfileController {

    private final EmployeeProfileService profileService;
    private final AbsenceService         absenceService;

    /**
     * POST /api/hr/profiles
     * Create a new employee profile.
     * Required: HR_MANAGER
     */
    @PostMapping
    @PreAuthorize("hasAuthority('HR_CREATE_PROFILE')")
    public ResponseEntity<EmployeeProfileResponseDto> create(
            @Valid @RequestBody EmployeeProfileCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(profileService.createProfile(dto, auth));
    }

    /**
     * GET /api/hr/profiles?page=0&size=20&pays=1&status=ACTIVE&department=Ingénierie&search=alice
     * Paginated list with optional filters and name search.
     * Required: any authenticated HR / manager / employee role.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<EmployeeProfileSummaryDto>> list(
            @RequestParam(required = false) Long   pays,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String contract,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {

        ProfileFilterDto filter = new ProfileFilterDto();
        filter.setPaysId(pays);
        filter.setStatus(status);
        filter.setDepartment(department);
        filter.setGrade(grade);
        filter.setContract(contract);
        filter.setSearch(search);

        Page<EmployeeProfileSummaryDto> page = profileService.listProfiles(filter, pageable);
        return ResponseEntity.ok(PageResponse.from(page));
    }

    /**
     * GET /api/hr/profiles/{id}
     * Full profile. Sensitive fields masked unless caller has HR_MANAGER / FINANCE_OFFICER.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmployeeProfileResponseDto> get(
            @PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(profileService.getProfile(id, auth));
    }

    /**
     * PATCH /api/hr/profiles/{id}
     * Partial update (PATCH semantics). Sensitive fields silently stripped for non-privileged callers.
     * Required: HR_MANAGER or HR_VIEWER (read-only roles cannot update).
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<EmployeeProfileResponseDto> update(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeProfileUpdateDto dto,
            Authentication auth) {
        return ResponseEntity.ok(profileService.updateProfile(id, dto, auth));
    }

    /**
     * POST /api/hr/profiles/{id}/lifecycle
     * Enforce state machine transition with mandatory reason.
     * Required: HR_MANAGER
     */
    @PostMapping("/{id}/lifecycle")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ARCHIVE_PROFILE')")
    public ResponseEntity<EmployeeProfileResponseDto> transition(
            @PathVariable Long id,
            @Valid @RequestBody LifecycleTransitionDto dto,
            Authentication auth) {
        return ResponseEntity.ok(profileService.transitionLifecycle(id, dto, auth));
    }

    /**
     * DELETE /api/hr/profiles/{id}
     * Soft archive: sets lifecycle_status=ARCHIVED and pseudonymises PII.
     * Required: HR_MANAGER
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('HR_ARCHIVE_PROFILE')")
    public void archive(@PathVariable Long id, Authentication auth) {
        profileService.archiveProfile(id, auth);
    }

    /**
     * GET /api/hr/profiles/next-employee-id
     * Le matricule est désormais généré automatiquement lors de la création du compte
     * MS365 (format [NOM3][PRE3][userId]). Cet endpoint retourne un placeholder
     * pour indiquer à l'interface que le matricule sera assigné automatiquement.
     */
    @GetMapping("/next-employee-id")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','ADMIN_ROLES','HR_ADMIN_ROLES')")
    public ResponseEntity<Map<String, String>> nextEmployeeId(@RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(Map.of(
            "employeeId", "AUTO",
            "info", "Matricule généré automatiquement lors du provisioning IT"
        ));
    }

    /**
     * GET /api/hr/profiles/employees
     * Paginated list of ALL active users (Users LEFT JOIN employee_profiles).
     * Administrateur (showAll=true) sees all entities; other HR roles filter by their paysId.
     */
    /**
     * GET /api/hr/profiles/employees
     * Paginated employee list with optional filters.
     * Params: page, size, sort, search, pays (Long id), status, department
     *         (label_fr), grade (label_fr), contract, hireDateFrom, hireDateTo.
     *
     * `department` / `grade` / `contract` / the hire-date window narrow on
     * employee_profiles, so a user with no HR profile yet drops out of the result
     * as soon as any of them is set — that is intended, those rows have nothing
     * to match on.
     */
    @GetMapping("/employees")
    // //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<Page<EmployeeListItemDto>> listAllEmployees(
            @RequestParam(required = false) Long   pays,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String contract,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hireDateTo,
            // No `sort` here: the query is hand-written JDBC and owns its ORDER BY
            // (newest hire first). A Pageable sort would be silently ignored, so
            // advertising one only invites a caller to trust it.
            @PageableDefault(size = 12) Pageable pageable,
            Authentication auth) {

        ProfileFilterDto filter = new ProfileFilterDto();
        filter.setSearch(search);
        filter.setStatus(status);
        filter.setPaysId(pays);
        filter.setDepartment(department);
        filter.setGrade(grade);
        filter.setContract(contract);
        filter.setHireDateFrom(hireDateFrom);
        filter.setHireDateTo(hireDateTo);

        return ResponseEntity.ok(profileService.listAllEmployees(filter, pageable));
    }

    /**
     * GET /api/hr/profiles/filter-options
     * Returns distinct filter values for the profile list dropdowns.
     */
    @GetMapping("/filter-options")
    // //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<FilterOptionsDto> getFilterOptions() {
        return ResponseEntity.ok(profileService.getFilterOptions());
    }

    /**
     * GET /api/hr/profiles/avatars?userIds=1,2,3
     *
     * <p>Batch user id → avatar reference. Consumed cross-module: the other services store
     * a person as a <b>user</b> id while the photo hangs off the RH <b>profile</b> and is
     * served by profile id, so without this a consumer had to search profiles by name or
     * fetch a whole profile per person. First caller is facturation's affaire detail page
     * ("Équipe projet").
     *
     * <p>Only authentication is required — no HR permission. This returns a name, a
     * profile id and a photo-presence flag, i.e. strictly less than what
     * {@code GET /api/hr/profiles/{id}/photo} already serves to anyone with no auth at all.
     * Gating it on {@code HR_*} would make every non-HR module unable to draw a face.
     *
     * <p>Unknown, profile-less or soft-deleted users are simply absent from the response
     * (or come back with a null {@code profileId}); the caller keys by {@code userId} and
     * falls back to initials. The batch is capped at 100 ids server-side.
     */
    @GetMapping("/avatars")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EmployeeAvatarDto>> resolveAvatars(
            @RequestParam List<Long> userIds) {
        return ResponseEntity.ok(profileService.resolveAvatars(userIds));
    }

    /**
     * PATCH /api/hr/profiles/users/{userId}
     * Update Users table fields (fullName, roleId) for a given user.
     */
    @PatchMapping("/users/{userId}")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','ADMIN_ROLES','HR_ADMIN_ROLES')")
    public ResponseEntity<Void> updateUserFields(
            @PathVariable Long userId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long actorId = auth != null && auth.getPrincipal() != null
                ? tryParseLong(auth.getPrincipal().toString()) : null;
        profileService.updateUserFields(userId, body, actorId);
        return ResponseEntity.noContent().build();
    }

    private Long tryParseLong(String s) {
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    // ── Leave balances ────────────────────────────────────────────────────────

    /**
     * GET /api/hr/profiles/{id}/leave-balances?annee={year}
     * Leave balance per type for the given employee and year.
     */
    @GetMapping("/{id}/leave-balances")
    public ResponseEntity<List<LeaveBalanceDto>> getLeaveBalances(
            @PathVariable Long id,
            @RequestParam(required = false) Integer annee) {
        return ResponseEntity.ok(absenceService.getLeaveBalances(id, annee));
    }

    // ── Photo ─────────────────────────────────────────────────────────────────

    /**
     * POST /api/hr/profiles/{id}/photo
     * Upload or replace the profile photo.
     * Accepts: multipart/form-data with field "file"
     */
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<EmployeeProfileResponseDto> uploadPhoto(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        return ResponseEntity.ok(profileService.uploadPhoto(id, file, auth));
    }

    /**
     * GET /api/hr/profiles/{id}/photo
     * Serve the profile photo — PUBLIC (no auth required, img tags can't send JWT).
     */
    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> servePhoto(@PathVariable Long id) {
        byte[] bytes = profileService.servePhoto(id);
        if (bytes == null || bytes.length == 0) {
            // `photo_url` is set on plenty of profiles whose file is missing from storage,
            // so this 404 is hit constantly — once per avatar, per render. An uncached 404
            // is re-requested every single time, which is why the console fills with them.
            // Caching the negative answer costs nothing and stops the hammering; a real
            // upload rewrites photo_url, so the URL changes and the cache is bypassed.
            return ResponseEntity.notFound()
                    .cacheControl(org.springframework.http.CacheControl
                            .maxAge(1, java.util.concurrent.TimeUnit.HOURS))
                    .build();
        }
        // Detect content type from first bytes (magic numbers)
        MediaType mediaType = MediaType.IMAGE_JPEG;
        if (bytes.length > 3 && bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50) {
            mediaType = MediaType.IMAGE_PNG;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(org.springframework.http.CacheControl.maxAge(7, java.util.concurrent.TimeUnit.DAYS))
                .body(bytes);
    }
}
