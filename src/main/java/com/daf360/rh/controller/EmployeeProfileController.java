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

import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/hr/profiles")
@RequiredArgsConstructor
public class EmployeeProfileController {

    private final EmployeeProfileService profileService;

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
    @GetMapping("/employees")
    // @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<Page<EmployeeListItemDto>> listAllEmployees(
            @RequestParam(required = false) Long   pays,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 25, sort = "fullName") Pageable pageable,
            Authentication auth) {

        ProfileFilterDto filter = new ProfileFilterDto();
        filter.setSearch(search);
        filter.setStatus(status);

        // If the caller does NOT have showAll rights, restrict to their own paysId
        boolean isShowAll = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ADMIN_ROLES")
                            || a.getAuthority().equals("HR_ADMIN_ROLES"));
        if (!isShowAll && pays == null && auth != null) {
            // Non-admin: filter will be set by explicit pays param from frontend
        }
        filter.setPaysId(pays);

        return ResponseEntity.ok(profileService.listAllEmployees(filter, pageable));
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
            return ResponseEntity.notFound().build();
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
