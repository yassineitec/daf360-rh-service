package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the SharePoint admin tab does beyond resolving: editing the configured paths,
 * browsing the real folder tree to pick them, listing which employees resolve, and pinning a
 * folder by hand when the convention cannot predict it.
 *
 * <p>The tab exists because every path change was previously hand-applied SQL on each
 * database — with no Flyway in this service, that meant a change could be applied on one
 * server and missed on another, silently disabling the feature there with no way to see it.
 * After this, a path is a form field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointAdminService {

    private final JdbcTemplate jdbc;
    private final SharePointLocationService locationService;
    private final EmployeeSharePointFolderStore folderStore;
    private final GraphSharePointService graph;
    private final com.daf360.rh.service.document.DocumentTypeService documentTypeService;

    /**
     * One employee's row in the status table.
     *
     * @param status null when this employee has never been resolved for the kind — distinct
     *               from a resolved failure, and the reason the batch action exists
     */
    public record EmployeeFolderRow(Long profileId, Long userId, String fullName, String paysIso,
                                    String folderSegment, String source, SharePointStatus status,
                                    OffsetDateTime resolvedAt, String lastError) {}

    /** A folder listing for the picker. */
    public record FolderListing(String path, List<String> folders) {}

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * As {@link #saveLocation(Long, DocKind, String, Long)}, for a kind identified by CODE —
     * which is how a document type arrives, since {@code document_types} (V87) grows at runtime
     * and no Java enum can name its rows.
     *
     * <p>Rejects a code that is neither a {@link DocKind} nor an active document type for this
     * country. Without that check the form would happily write a row for a typo'd code: the
     * insert succeeds, the path looks configured in the table, and nothing ever reads it.
     *
     * <p>Document types are validated as non-year-scoped ({@code {employeeFolder}} required,
     * {@code {year}} refused) — a per-year document folder would have to exist before January's
     * first upload, and nothing creates it.
     *
     * @return validation problems; empty means saved
     */
    public List<String> saveLocation(Long paysId, String kindCode, String pathTemplate, Long actorId) {
        Optional<DocKind> known = DocKind.from(kindCode);
        if (known.isPresent()) {
            return saveLocation(paysId, known.get(), pathTemplate, actorId);
        }
        String code = kindCode == null ? "" : kindCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (documentTypeService.validate(paysId, code).isEmpty()) {
            return List.of("SHAREPOINT.TEMPLATE.UNKNOWN_KIND");
        }
        List<String> problems = new java.util.ArrayList<>();
        String t = pathTemplate == null ? "" : pathTemplate.trim();
        if (t.isBlank()) {
            problems.add("SHAREPOINT.TEMPLATE.BLANK");
        } else {
            if (!t.contains(SharePointPaths.EMPLOYEE_FOLDER_TOKEN)) {
                problems.add("SHAREPOINT.TEMPLATE.MISSING_EMPLOYEE_FOLDER");
            }
            if (t.contains(SharePointPaths.YEAR_TOKEN)) {
                problems.add("SHAREPOINT.TEMPLATE.YEAR_NOT_ALLOWED");
            }
        }
        if (!problems.isEmpty()) return problems;
        return upsertLocation(paysId, code, t, actorId);
    }

    /**
     * Creates or replaces the path for one country and enum kind.
     *
     * <p>Validation is the same {@link DocKind#validateTemplate} the resolver applies, so the
     * form cannot save a template the resolver would then silently refuse — which would show up
     * as "the folder is missing" and send someone hunting through SharePoint for a fault that is
     * actually one bad row.
     *
     * @return the validation problems; empty means saved
     */
    public List<String> saveLocation(Long paysId, DocKind kind, String pathTemplate, Long actorId) {
        List<String> problems = kind.validateTemplate(pathTemplate);
        if (!problems.isEmpty()) return problems;
        return upsertLocation(paysId, kind.name(), pathTemplate.trim(), actorId);
    }

    /**
     * The write itself, shared by both {@code saveLocation} overloads so an enum kind and a
     * document-type code cannot drift into two different UPSERTs — the table has one unique
     * key, {@code (pays_id, doc_kind)}, and one writer is what keeps that true.
     */
    private List<String> upsertLocation(Long paysId, String kindCode, String template, Long actorId) {
        int updated = jdbc.update(
                "UPDATE [dbo].[sharepoint_locations] "
                + "SET path_template = ?, is_active = 1, updated_at = SYSDATETIMEOFFSET(), updated_by = ? "
                + "WHERE pays_id = ? AND doc_kind = ?",
                template, actorId, paysId, kindCode);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO [dbo].[sharepoint_locations] "
                    + "(pays_id, doc_kind, path_template, is_active, updated_at, updated_by) "
                    + "VALUES (?, ?, ?, 1, SYSDATETIMEOFFSET(), ?)",
                    paysId, kindCode, template, actorId);
        }
        // Without this the edit is invisible for up to the config cache's TTL, and a stale
        // path is exactly the confusion this tab exists to remove.
        locationService.evictAll();
        log.info("Chemin SharePoint {} / pays {} enregistre par l utilisateur {} : {}",
                kindCode, paysId, actorId, template);
        return List.of();
    }

    /**
     * Removes a configured path.
     *
     * <p>Leaves the resolution cache alone on purpose: deleting a path is usually a correction
     * about to be followed by a new one, and throwing away 130 resolved folder segments to
     * rediscover them a minute later is a Graph bill for nothing. The rows become unreachable
     * while no path exists, and are reused as soon as one does.
     */
    public void deleteLocation(Long id) {
        jdbc.update("DELETE FROM [dbo].[sharepoint_locations] WHERE id = ?", id);
        locationService.evictAll();
        log.info("Chemin SharePoint {} supprime", id);
    }

    // ── Folder browser ────────────────────────────────────────────────────────

    /**
     * Immediate subfolders of a path, for the picker.
     *
     * @throws IllegalArgumentException when the path is not a safe relative path — see
     *         {@link SharePointPaths#isSafeRelativePath}. Thrown rather than sanitised: this
     *         value goes into a Graph URL that can reach the entire site.
     */
    public FolderListing browse(String path) {
        String clean = path == null ? "" : path.trim();
        if (!SharePointPaths.isSafeRelativePath(clean)) {
            throw new IllegalArgumentException("chemin invalide");
        }
        return new FolderListing(clean, graph.listFolders(clean));
    }

    // ── Employee status table ─────────────────────────────────────────────────

    /**
     * Every employee and how their folder resolves for one kind.
     *
     * <p>Reads only the database — no Graph call, whatever the row count. That is what makes
     * the table openable at will; refreshing what it shows is the batch resolve, which is a
     * separate, explicit action because it does cost a Graph call per employee.
     *
     * <p>A null status is the interesting case: it means nobody has ever looked. Before the
     * batch action existed that was the state of ~128 of 130 employees, and the only ones
     * resolved were those whose profile page somebody had happened to open.
     */
    public List<EmployeeFolderRow> employeeRows(KindRef kind) {
        Map<Long, EmployeeSharePointFolderStore.CachedFolder> cached = new HashMap<>();
        for (EmployeeSharePointFolderStore.CachedFolder row : folderStore.findAll(kind)) {
            cached.put(row.employeeProfileId(), row);
        }

        List<EmployeeFolderRow> rows = new ArrayList<>();
        jdbc.query(
                "SELECT ep.id AS profile_id, ep.user_id, u.fullName, p.iso_code "
                + "FROM [dbo].[employee_profiles] ep "
                + "JOIN [dbo].[Users] u ON u.id = ep.user_id "
                + "LEFT JOIN [dbo].[pays] p ON p.id = ep.pays_id "
                + "WHERE ep.deleted = 0 "
                + "ORDER BY u.fullName",
                rs -> {
                    Long profileId = rs.getLong("profile_id");
                    EmployeeSharePointFolderStore.CachedFolder hit = cached.get(profileId);
                    rows.add(new EmployeeFolderRow(
                            profileId,
                            rs.getLong("user_id"),
                            rs.getString("fullName"),
                            rs.getString("iso_code"),
                            hit == null ? null : hit.folderSegment(),
                            hit == null ? null : hit.source().name(),
                            hit == null ? null : hit.status(),
                            hit == null ? null : hit.resolvedAt(),
                            hit == null ? null : hit.lastError()));
                });
        return rows;
    }

    // ── Manual override ───────────────────────────────────────────────────────

    /**
     * Pins an employee's folder segment by hand.
     *
     * <p>For the folders no convention predicts — the live tree holds
     * {@code "Bilel ZEDINI-CDI-ARX tunisie"} under 03_Payroll-Admin, where every sibling is
     * {@code "Firstname LASTNAME"}. Discovery will never find that, and teaching it to guess
     * would mean prefix or fuzzy matching on a folder full of payslips.
     *
     * <p>Validated against the tree before it is stored: an override pointing at a folder that
     * does not exist is worse than none, because it looks configured and silently resolves to
     * nothing.
     *
     * @return empty when stored, otherwise the reason it was refused
     */
    public Optional<String> pinFolder(Long profileId, KindRef kind, String folderSegment) {
        String segment = folderSegment == null ? "" : folderSegment.trim();
        if (segment.isEmpty() || !SharePointPaths.isSafeRelativePath(segment)
                || segment.contains("/")) {
            return Optional.of("SHAREPOINT.OVERRIDE.INVALID_SEGMENT");
        }

        Long paysId = jdbc.queryForObject(
                "SELECT pays_id FROM [dbo].[employee_profiles] WHERE id = ?", Long.class, profileId);
        Optional<String> template = locationService.templateForCode(paysId, kind.code());
        if (template.isEmpty()) {
            return Optional.of("SHAREPOINT.OVERRIDE.NO_CONFIG");
        }

        String path = template.get().replace(SharePointPaths.EMPLOYEE_FOLDER_TOKEN, segment);
        String basePath = kind.yearScoped()
                ? SharePointPaths.stripFrom(path, SharePointPaths.YEAR_TOKEN)
                : path;
        if (!graph.folderExists(basePath)) {
            return Optional.of("SHAREPOINT.OVERRIDE.FOLDER_MISSING");
        }

        folderStore.saveManual(profileId, kind, segment);
        return Optional.empty();
    }

    /** Forgets an employee's resolution, override included, so the next lookup starts over. */
    public void clearFolder(Long profileId, KindRef kind) {
        folderStore.clear(profileId, kind);
        log.info("Resolution SharePoint {} / profil {} reinitialisee", kind, profileId);
    }
}
