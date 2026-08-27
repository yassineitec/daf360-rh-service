package com.daf360.rh.controller;

import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.SharePointAdminService;
import com.daf360.rh.service.sharepoint.SharePointLocationService;
import com.daf360.rh.service.sharepoint.SharePointResolver;
import com.daf360.rh.service.sharepoint.SharePointStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The SharePoint administration API: configure paths, browse the tree to pick them, see which
 * employees resolve, and pin a folder by hand.
 *
 * <p>Replaces the phase-A diagnostic controller, whose two read endpoints are folded in below.
 *
 * <p>Gated on {@code ADMIN_SHAREPOINT} (V86), with {@code ADMIN_ROLES}/{@code HR_ADMIN_ROLES}
 * still accepted. The overlap is deliberate rather than lazy: applying V86 and deploying this
 * code are two separate manual steps, and whichever happens first must not lock an
 * administrator out of the tab. It also keeps the phase-A endpoints reachable for whoever was
 * already using them.
 */
@RestController
@RequestMapping("/api/hr/sharepoint")
@RequiredArgsConstructor
public class SharePointAdminController {

    private static final String ADMIN =
            "hasAnyAuthority('ADMIN_SHAREPOINT','ADMIN_ROLES','HR_ADMIN_ROLES')";

    /** Year folders listed for a diagnosis. Enough to see the shape, not the whole history. */
    private static final int YEAR_SAMPLE = 5;

    private final SharePointResolver resolver;
    private final SharePointLocationService locationService;
    private final SharePointAdminService adminService;
    private final GraphSharePointService graph;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** @param problems empty when the stored template is valid — a non-empty list is why the
     *                  resolver treats this row as "no configuration" */
    public record LocationDto(Long id, Long paysId, String isoCode, String docKind,
                              String pathTemplate, boolean active, List<String> problems) {}

    @Data
    public static class SaveLocationRequest {
        @NotNull  private Long   paysId;
        @NotBlank private String docKind;
        @NotBlank private String pathTemplate;
    }

    @Data
    public static class PinFolderRequest {
        @NotBlank private String docKind;
        @NotBlank private String folderSegment;
    }

    public record FolderListingDto(String path, List<String> folders) {}

    public record EmployeeRowDto(Long profileId, Long userId, String fullName, String paysIso,
                                 String folderSegment, String source, SharePointStatus status,
                                 OffsetDateTime resolvedAt, String lastError) {}

    public record DiagnosisDto(Long profileId, String docKind, SharePointStatus status,
                              String path, String basePath, String employeeFolder,
                              String source, String detail, boolean graphConfigured,
                              List<String> files, List<String> years) {}

    public record BatchRowDto(Long profileId, String employeeFolder, SharePointStatus status,
                              String source, String detail) {}

    /** @param byStatus counts per status — the headline an operator actually reads */
    public record BatchResultDto(String docKind, int total, Map<String, Long> byStatus,
                                 List<BatchRowDto> rows) {}

    /** The kinds this build understands, so the form offers exactly what the resolver supports. */
    public record KindDto(String code, boolean yearScoped) {}

    // ── Reference ─────────────────────────────────────────────────────────────

    @GetMapping("/kinds")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<KindDto>> kinds() {
        return ResponseEntity.ok(java.util.Arrays.stream(DocKind.values())
                .map(k -> new KindDto(k.name(), k.isYearScoped()))
                .toList());
    }

    // ── Panel 1: configured paths ─────────────────────────────────────────────

    /**
     * Every configured path, with its validation problems if any.
     *
     * <p>Reports problems rather than hiding invalid rows: a template the resolver rejects
     * looks identical to no configuration at all from the outside, and that is precisely the
     * confusion worth eliminating.
     */
    @GetMapping("/locations")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<LocationDto>> locations() {
        return ResponseEntity.ok(locationService.listAll().stream()
                .map(r -> new LocationDto(
                        r.id(), r.paysId(), r.isoCode(),
                        r.docKind() == null ? null : r.docKind().name(),
                        r.pathTemplate(), r.active(),
                        r.docKind() == null
                                ? List.of("SHAREPOINT.TEMPLATE.UNKNOWN_KIND")
                                : r.docKind().validateTemplate(r.pathTemplate())))
                .toList());
    }

    /** Creates or replaces one country's path for one kind. 422 carries the validation keys. */
    @PutMapping("/locations")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<String>> saveLocation(@RequestBody SaveLocationRequest request,
                                                     Authentication auth) {
        Optional<DocKind> kind = locationService.parseKind(request.getDocKind());
        if (kind.isEmpty()) {
            return ResponseEntity.badRequest().body(List.of("SHAREPOINT.TEMPLATE.UNKNOWN_KIND"));
        }
        List<String> problems = adminService.saveLocation(
                request.getPaysId(), kind.get(), request.getPathTemplate(), actorId(auth));
        return problems.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.unprocessableEntity().body(problems);
    }

    @DeleteMapping("/locations/{id}")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Void> deleteLocation(@PathVariable Long id) {
        adminService.deleteLocation(id);
        return ResponseEntity.noContent().build();
    }

    // ── Folder browser ────────────────────────────────────────────────────────

    /**
     * Immediate subfolders of a path, so a path can be picked in the app instead of copied out
     * of a SharePoint tab. Omit {@code path} for the drive root.
     *
     * <p>400 on a rejected path rather than a sanitised one: this value is interpolated into a
     * Graph URL with reach over the whole site, so a path that needs cleaning is a path the
     * caller did not mean.
     */
    @GetMapping("/folders")
    @PreAuthorize(ADMIN)
    public ResponseEntity<FolderListingDto> folders(
            @RequestParam(required = false) String path) {
        try {
            SharePointAdminService.FolderListing listing = adminService.browse(path);
            return ResponseEntity.ok(new FolderListingDto(listing.path(), listing.folders()));
        } catch (IllegalArgumentException rejected) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Panel 2: who resolves, who does not ───────────────────────────────────

    /** The status table. Database only — no Graph call, whatever the row count. */
    @GetMapping("/employees")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<EmployeeRowDto>> employees(@RequestParam String docKind) {
        Optional<DocKind> kind = locationService.parseKind(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();

        return ResponseEntity.ok(adminService.employeeRows(kind.get()).stream()
                .map(r -> new EmployeeRowDto(r.profileId(), r.userId(), r.fullName(), r.paysIso(),
                        r.folderSegment(), r.source(), r.status(), r.resolvedAt(), r.lastError()))
                .toList());
    }

    /**
     * Resolves every employee for one kind and reports the tally plus every row.
     *
     * <p>The operation that replaces "hope somebody opens the right page": resolution used to
     * happen only as a side effect of a request, so on the live tenant two employees out of
     * ~130 had a located photo and the rest had simply never been asked about.
     *
     * <p>POST because it writes the resolution cache, and sequential inside because a parallel
     * fan-out of 130 Graph lookups earns a 429 and a screenful of failures that say nothing
     * about the folders.
     */
    @PostMapping("/resolve")
    @PreAuthorize(ADMIN)
    public ResponseEntity<BatchResultDto> resolveAll(
            @RequestParam String docKind,
            @RequestParam(defaultValue = "false") boolean force) {

        Optional<DocKind> kind = locationService.parseKind(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();

        List<SharePointResolver.BatchRow> rows = resolver.resolveAll(kind.get(), force);
        Map<String, Long> byStatus = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.status().name(), java.util.stream.Collectors.counting()));

        return ResponseEntity.ok(new BatchResultDto(kind.get().name(), rows.size(), byStatus,
                rows.stream()
                        .map(r -> new BatchRowDto(r.profileId(), r.employeeFolder(), r.status(),
                                r.source() == null ? null : r.source().name(), r.detail()))
                        .toList()));
    }

    // ── Manual override ───────────────────────────────────────────────────────

    /**
     * Pins one employee's folder segment. Checked against the tree first — an override
     * pointing at a folder that does not exist is worse than none, because it looks configured
     * and resolves to nothing.
     */
    @PutMapping("/employees/{profileId}/folder")
    @PreAuthorize(ADMIN)
    public ResponseEntity<String> pinFolder(@PathVariable Long profileId,
                                            @RequestBody PinFolderRequest request) {
        Optional<DocKind> kind = locationService.parseKind(request.getDocKind());
        if (kind.isEmpty()) {
            return ResponseEntity.badRequest().body("SHAREPOINT.TEMPLATE.UNKNOWN_KIND");
        }
        return adminService.pinFolder(profileId, kind.get(), request.getFolderSegment())
                .map(problem -> ResponseEntity.unprocessableEntity().body(problem))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Forgets an employee's resolution, override included, so the next lookup starts over. */
    @DeleteMapping("/employees/{profileId}/folder")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Void> clearFolder(@PathVariable Long profileId,
                                            @RequestParam String docKind) {
        Optional<DocKind> kind = locationService.parseKind(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();
        adminService.clearFolder(profileId, kind.get());
        return ResponseEntity.noContent().build();
    }

    // ── Panel 3: diagnosis ────────────────────────────────────────────────────

    /**
     * Resolves one employee for one kind and reports everything known about it — the answer to
     * "why is this person's document missing", which previously meant reading container logs
     * and hand-crafting Graph calls.
     *
     * @param force re-run discovery, ignoring both caches. Use after correcting a folder in
     *              SharePoint; without it a remembered failure is trusted for 24h.
     */
    @GetMapping("/diagnose")
    @PreAuthorize(ADMIN)
    public ResponseEntity<DiagnosisDto> diagnose(
            @RequestParam Long profileId,
            @RequestParam String docKind,
            @RequestParam(defaultValue = "false") boolean force) {

        Optional<DocKind> kind = locationService.parseKind(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();

        SharePointResolver.ResolvedLocation loc = resolver.resolve(profileId, kind.get(), force);

        List<String> files = List.of();
        List<String> years = List.of();
        if (loc.isFound()) {
            years = kind.get().isYearScoped() ? resolver.years(loc, YEAR_SAMPLE) : List.of();
            // For a year-scoped kind the files live under a year, not under basePath, so list
            // the newest year rather than reporting an empty folder that is not empty.
            String listAt = kind.get().isYearScoped()
                    ? (years.isEmpty() ? null
                                       : loc.path().replace(
                                             com.daf360.rh.service.sharepoint.SharePointPaths.YEAR_TOKEN,
                                             years.get(0)))
                    : loc.basePath();
            if (listAt != null) {
                files = graph.listFiles(listAt).stream()
                        .map(GraphSharePointService.RemoteFile::name)
                        .toList();
            }
        }

        return ResponseEntity.ok(new DiagnosisDto(
                profileId, kind.get().name(), loc.status(), loc.path(), loc.basePath(),
                loc.employeeFolder(), loc.source() == null ? null : loc.source().name(),
                loc.detail(), graph.isConfigured(), files, years));
    }

    /**
     * Same convention as MissionController: the authentication principal is the user id.
     *
     * <p>Null rather than an AccessDeniedException, unlike there, because this only feeds
     * {@code updated_by} on an audit column. Refusing a valid path edit over an unreadable
     * principal would be trading a working feature for a nicer log line.
     */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
