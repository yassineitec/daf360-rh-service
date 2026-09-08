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
import org.springframework.http.HttpHeaders;
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
    private final com.daf360.rh.service.sharepoint.SharePointDeltaService deltaSync;

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
            // Existaient dans ProfileFilterDto et étaient déjà envoyées par l'écran, mais
            // n'étaient acceptées nulle part : régler une période de recrutement ne filtrait
            // rien, sans le moindre signe.
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate hireDateFrom,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate hireDateTo,
            // Faux par défaut : la liste est l'effectif présent (ACTIVE / ON_LEAVE /
            // ON_MISSION). Vrai rouvre les profils sortis — c'est la seule porte vers un
            // TERMINATED ou un ARCHIVED depuis l'application.
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @PageableDefault(size = 20) Pageable pageable) {

        ProfileFilterDto filter = new ProfileFilterDto();
        filter.setPaysId(pays);
        filter.setStatus(status);
        filter.setDepartment(department);
        filter.setGrade(grade);
        filter.setContract(contract);
        filter.setSearch(search);
        filter.setHireDateFrom(hireDateFrom);
        filter.setHireDateTo(hireDateTo);
        filter.setIncludeInactive(includeInactive);

        // Ask SharePoint what changed, at most once every 30s across the whole platform and
        // always on a background thread — no request waits for it. One Graph call covers every
        // employee, so this is what keeps a list within seconds of the tree instead of 24h.
        deltaSync.syncIfStale();

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
            // Faux par défaut : « les employés », ce sont ceux qui sont en service. Voir
            // EmployeeProfileService.listAllEmployees pour les statuts retenus.
            @RequestParam(defaultValue = "false") boolean includeInactive,
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
        filter.setIncludeInactive(includeInactive);

        // Same background delta check as the paginated list above: this is the endpoint the
        // profiles grid and the annuaire actually call.
        deltaSync.syncIfStale();
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
    public ResponseEntity<byte[]> servePhoto(
            @PathVariable Long id,
            @RequestParam(required = false) String size,
            @RequestParam(required = false) Boolean fresh,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        boolean small = "sm".equalsIgnoreCase(size);

        // fresh=1 re-reads SharePoint for THIS employee, ignoring the 24h revalidation window.
        // Affordable because it is one person: 2 Graph calls, on an image request that loads
        // asynchronously and blocks nothing. The detail page uses it so the profile you are
        // actually looking at is never stale. A LIST must never pass it — twelve avatars would
        // be 24 Graph calls per page view, which is what the delta sync exists to avoid.
        if (Boolean.TRUE.equals(fresh)) {
            profileService.refreshPhotoFromSharePoint(id);
        }

        // ── Conditional request: answer 304 before doing any work ─────────────────────────
        // This response used to advertise max-age=7d, which is OPAQUE caching: within a week a
        // browser did not even ask, so a replaced photo stayed invisible on every machine that
        // had loaded the old one — and the only escape was the ?v= token in photo_url, which any
        // caller that rebuilt the URL by hand silently dropped (two of them did).
        //
        // Validation-based caching removes the whole class of bug: browsers always ask, and an
        // unchanged photo costs a few hundred bytes of 304 instead of an image. A change is then
        // visible to EVERY user on their next page load, with no token and no deploy involved.
        String etag = profileService.photoEtag(id, small).orElse(null);
        if (etag != null && ifNoneMatch != null && matchesEtag(ifNoneMatch, etag)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(org.springframework.http.CacheControl.noCache())
                    .build();
        }
        // size=sm serves the 128px variant. The cache keeps 512px, which is 4x what the grid's
        // 112px circle shows and 16x the annuaire's 32px one — twelve of those is most of what
        // the profiles page downloads. Any other value (including none) means full size, so an
        // old client or a typo degrades to the previous behaviour rather than to no avatar.
        byte[] bytes = profileService.servePhoto(id, small);
        if (bytes == null || bytes.length == 0) {
            // `photo_url` is set on plenty of profiles whose file is missing from storage, so
            // this 404 is hit constantly — once per avatar, per render — and an uncached 404 is
            // re-requested every single time, which is what filled the console.
            //
            // 60 SECONDS, not the hour this used to be: the hour also hid a photo that had just
            // been added, on every browser that happened to ask first, which is the same
            // staleness the ETag above exists to eliminate. A minute still absorbs the render
            // storm (the point of caching it at all) without making a new photo wait.
            return ResponseEntity.notFound()
                    .cacheControl(org.springframework.http.CacheControl
                            .maxAge(60, java.util.concurrent.TimeUnit.SECONDS))
                    .build();
        }
        // Detect content type from first bytes (magic numbers)
        MediaType mediaType = MediaType.IMAGE_JPEG;
        if (bytes.length > 3 && bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50) {
            mediaType = MediaType.IMAGE_PNG;
        }
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .contentType(mediaType)
                // noCache = "you may keep it, but ask before reusing it". Paired with the ETag,
                // that ask is answered by a 304 in the common case, so this is cheaper than it
                // looks: the bytes cross the wire only when the photo has actually changed.
                .cacheControl(org.springframework.http.CacheControl.noCache());

        // Recomputed rather than reusing the tag from before servePhoto(): that call can FETCH
        // and cache a photo that was not on disk yet (or refresh a stale one), in which case the
        // earlier tag described a file that no longer exists — and handing the client a tag that
        // never matches again would make every later request a full download.
        profileService.photoEtag(id, small).ifPresent(ok::eTag);
        return ok.body(bytes);
    }

    /**
     * Whether an {@code If-None-Match} header covers our tag.
     *
     * <p>Handles the three shapes a browser actually sends: the tag alone, a comma-separated
     * list of tags, and {@code *}. Weak comparison — the {@code W/} prefix is ignored on both
     * sides, which is what the spec requires for a GET and what makes our own weak tags usable
     * at all.
     */
    private static boolean matchesEtag(String ifNoneMatch, String etag) {
        String mine = etag.replaceFirst("^W/", "");
        for (String candidate : ifNoneMatch.split(",")) {
            String theirs = candidate.trim().replaceFirst("^W/", "");
            if (theirs.equals("*") || theirs.equals(mine)) return true;
        }
        return false;
    }
}
