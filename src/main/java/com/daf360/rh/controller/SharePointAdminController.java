package com.daf360.rh.controller;

import com.daf360.rh.service.document.DocumentTypeService;
import com.daf360.rh.service.sharepoint.DocKind;
import com.daf360.rh.service.sharepoint.GraphSharePointService;
import com.daf360.rh.service.sharepoint.KindRef;
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
    private final com.daf360.rh.service.photo.ProfilePhotoWarmupService photoWarmup;
    private final DocumentTypeService documentTypeService;

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

    @Data
    public static class SaveDocumentTypeRequest {
        @NotNull  private Long    paysId;
        @NotBlank private String  code;
        @NotBlank private String  labelFr;
                  private String  labelEn;
        /** Null means active -- creating a type you cannot select would be a strange default. */
                  private Boolean active;
                  private Integer sortOrder;
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

    /**
     * One configurable kind offered by the path form.
     *
     * @param builtIn true for a {@link DocKind} -- those have behaviour attached (year scoping,
     *                their own template rules) and cannot be added or removed from the UI;
     *                false for a document type, which is just a row
     * @param label   what to show: the enum name for a built-in kind, the configured French
     *                label for a document type
     */
    public record KindDto(String code, boolean yearScoped, boolean builtIn, String label) {}

    // ── Reference ─────────────────────────────────────────────────────────────

    /**
     * The kinds a path can be configured for: the built-in ones, plus this country's document
     * types when {@code paysId} is given.
     *
     * <p>Document types are per country ({@code document_types}, V87), so they can only be
     * offered once a country is chosen. Without them the form could configure paths for three
     * kinds while the table held thirty-odd document rows it could not name — which is precisely
     * what made document folders uneditable here.
     *
     * @param paysId optional; omit for the built-in kinds alone
     */
    @GetMapping("/kinds")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<KindDto>> kinds(@RequestParam(required = false) Long paysId) {
        List<KindDto> kinds = new java.util.ArrayList<>(
                java.util.Arrays.stream(DocKind.values())
                        .map(k -> new KindDto(k.name(), k.isYearScoped(), true, k.name()))
                        .toList());
        if (paysId != null) {
            for (var t : documentTypeService.forPays(paysId)) {
                // PHOTO exists in both vocabularies and shares one row in sharepoint_locations
                // (the key is (pays_id, doc_kind)). Listing it twice would offer the same row
                // under two entries and let a form overwrite the profile-photo path by accident.
                if (DocKind.from(t.code()).isPresent()) continue;
                kinds.add(new KindDto(t.code(), false, false, t.labelFr()));
            }
        }
        return ResponseEntity.ok(kinds);
    }

    // ── Panel 0: the document-type vocabulary ─────────────────────────────────

    /** Every type for a country, deactivated ones included — they must be reactivatable. */
    @GetMapping("/document-types")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<DocumentTypeService.AdminRow>> documentTypes(@RequestParam Long paysId) {
        return ResponseEntity.ok(documentTypeService.listForAdmin(paysId));
    }

    /** Creates or updates one type. 422 carries the validation keys. */
    @PutMapping("/document-types")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<String>> saveDocumentType(@RequestBody SaveDocumentTypeRequest req,
                                                         Authentication auth) {
        List<String> problems = documentTypeService.save(
                req.getPaysId(), req.getCode(), req.getLabelFr(), req.getLabelEn(),
                req.getActive() == null || req.getActive(), req.getSortOrder(), actorId(auth));
        return problems.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.unprocessableEntity().body(problems);
    }

    /**
     * Deactivates a type — there is no delete.
     *
     * <p>Documents already filed store the code with no foreign key behind it, so removing the
     * row would label them with something nothing can resolve. Deactivation takes it out of the
     * dropdown and leaves every existing document readable.
     */
    @DeleteMapping("/document-types/{id}")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Void> deactivateDocumentType(@PathVariable Long id, Authentication auth) {
        documentTypeService.deactivate(id, actorId(auth));
        return ResponseEntity.noContent().build();
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
                // kindCode, not docKind().name(): V87 fills this table with document-type codes
                // that have no enum constant, and reporting those as UNKNOWN_KIND made 32 valid
                // rows look broken. Validation routes on the code instead.
                .map(r -> new LocationDto(
                        r.id(), r.paysId(), r.isoCode(), r.kindCode(),
                        r.pathTemplate(), r.active(),
                        locationService.validateTemplateForCode(r.kindCode(), r.pathTemplate())))
                .toList());
    }

    /** Creates or replaces one country's path for one kind. 422 carries the validation keys. */
    @PutMapping("/locations")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<String>> saveLocation(@RequestBody SaveLocationRequest request,
                                                     Authentication auth) {
        // Code, not DocKind: a document type from document_types (V87) has no enum constant,
        // and rejecting it here is what used to make document paths uneditable in this tab.
        // The service validates the code against both vocabularies.
        List<String> problems = adminService.saveLocation(
                request.getPaysId(), request.getDocKind(), request.getPathTemplate(), actorId(auth));
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

    /**
     * The status table. Database only — no Graph call, whatever the row count.
     *
     * <p>{@code docKind} is a CODE from either vocabulary, so 400 here now means malformed, not
     * "not one of the three enum kinds". That distinction is the whole point of {@link KindRef}:
     * this endpoint used to reject every {@code document_types} row, which made the panel
     * unusable for the 15-odd document types whose paths the form next door could already save.
     * An unconfigured kind is not an error — it resolves to NO_CONFIG per employee, which is
     * exactly what this table exists to show.
     */
    @GetMapping("/employees")
    @PreAuthorize(ADMIN)
    public ResponseEntity<List<EmployeeRowDto>> employees(@RequestParam String docKind) {
        Optional<KindRef> kind = KindRef.parse(docKind);
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

        Optional<KindRef> kind = KindRef.parse(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();

        List<SharePointResolver.BatchRow> rows = resolver.resolveAll(kind.get(), force);

        // Knowing WHERE the photo is does not put it on the page: the bytes are still fetched
        // lazily on first view, two Graph calls deep, and photo_url stays NULL so the frontend
        // never asks. Warming follows the resolve for PHOTO, in the BACKGROUND — a hundred
        // sequential downloads outlive any reverse-proxy read timeout, and the admin needs the
        // resolve verdict now, not in three minutes.
        if (DocKind.PHOTO.name().equals(kind.get().code())) {
            photoWarmup.warmInBackground("resolution admin");
        }
        Map<String, Long> byStatus = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        r -> r.status().name(), java.util.stream.Collectors.counting()));

        return ResponseEntity.ok(new BatchResultDto(kind.get().code(), rows.size(), byStatus,
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
        Optional<KindRef> kind = KindRef.parse(request.getDocKind());
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
        Optional<KindRef> kind = KindRef.parse(docKind);
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

        Optional<KindRef> kind = KindRef.parse(docKind);
        if (kind.isEmpty()) return ResponseEntity.badRequest().build();

        SharePointResolver.ResolvedLocation loc = resolver.resolve(profileId, kind.get(), force);

        List<String> files = List.of();
        List<String> years = List.of();
        if (loc.isFound()) {
            years = kind.get().yearScoped() ? resolver.years(loc, YEAR_SAMPLE) : List.of();
            // For a year-scoped kind the files live under a year, not under basePath, so list
            // the newest year rather than reporting an empty folder that is not empty.
            String listAt = kind.get().yearScoped()
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
                profileId, kind.get().code(), loc.status(), loc.path(), loc.basePath(),
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
