package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The {@code employee_sharepoint_folders} cache (V84): which folder each employee's documents
 * were actually found in, per document kind.
 *
 * <p>Deliberately sparse. No row means "not looked up yet" — not "no folder" — so the table
 * stays small and can be truncated at any time without losing anything that cannot be
 * rediscovered. The one exception is {@link ResolutionSource#MANUAL}, which is the only
 * information here a human supplied, and the only row a truncate would really destroy.
 *
 * <p>It also caches NEGATIVE results, with their reason. That is not an optimisation detail:
 * without it, an employee with no photo costs roughly five Graph calls on every avatar render,
 * so a directory page of a hundred such employees is a throttling incident waiting to happen —
 * and the same quota is what payslip listings will draw on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeSharePointFolderStore {

    /** {@code last_error} is NVARCHAR(500); a Graph message can be longer. */
    private static final int MAX_ERROR_LEN = 500;

    private final JdbcTemplate jdbc;

    /** @param folderSegment the real folder name, null when the lookup failed */
    public record CachedFolder(Long employeeProfileId, DocKind docKind, String folderSegment,
                               ResolutionSource source, SharePointStatus status,
                               OffsetDateTime resolvedAt, String lastError) {

        public boolean isManual() { return source == ResolutionSource.MANUAL; }

        public boolean isUsable() { return folderSegment != null && !folderSegment.isBlank(); }
    }

    public Optional<CachedFolder> find(Long employeeProfileId, DocKind kind) {
        if (employeeProfileId == null || kind == null) return Optional.empty();
        try {
            List<CachedFolder> rows = jdbc.query(
                    "SELECT employee_profile_id, doc_kind, folder_segment, source, status, "
                    + "resolved_at, last_error "
                    + "FROM [dbo].[employee_sharepoint_folders] "
                    + "WHERE employee_profile_id = ? AND doc_kind = ?",
                    (rs, i) -> map(rs), employeeProfileId, kind.name());
            return rows.stream().findFirst();
        } catch (Exception e) {
            // V84 not applied on this server, most likely. Resolution still works, it just
            // rediscovers every time: degraded, not broken.
            log.debug("Lecture de employee_sharepoint_folders impossible ({}) - resolution "
                      + "sans cache", e.getMessage());
            return Optional.empty();
        }
    }

    /** Every cached row for a kind, for the admin panel's "who resolves, who does not" table. */
    public List<CachedFolder> findAll(DocKind kind) {
        if (kind == null) return List.of();
        try {
            return jdbc.query(
                    "SELECT employee_profile_id, doc_kind, folder_segment, source, status, "
                    + "resolved_at, last_error "
                    + "FROM [dbo].[employee_sharepoint_folders] WHERE doc_kind = ?",
                    (rs, i) -> map(rs), kind.name());
        } catch (Exception e) {
            log.debug("Listage de employee_sharepoint_folders impossible: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Records the outcome of a discovery, success or failure.
     *
     * <p>Cannot touch a MANUAL row: the UPDATE is scoped to {@code source = 'DISCOVERED'}, and
     * the INSERT that follows a zero-row update then fails on the primary key, because a
     * MANUAL row is exactly what blocked the update. So the protection is enforced by the
     * database rather than by a check this method could be refactored out of.
     */
    public void saveDiscovered(Long employeeProfileId, DocKind kind, String folderSegment,
                               SharePointStatus status, String detail) {
        if (employeeProfileId == null || kind == null || status == null) return;
        try {
            int updated = jdbc.update(
                    "UPDATE [dbo].[employee_sharepoint_folders] "
                    + "SET folder_segment = ?, status = ?, last_error = ?, "
                    + "resolved_at = SYSDATETIMEOFFSET() "
                    + "WHERE employee_profile_id = ? AND doc_kind = ? AND source = 'DISCOVERED'",
                    folderSegment, status.name(), truncate(detail), employeeProfileId, kind.name());
            if (updated > 0) return;

            jdbc.update(
                    "INSERT INTO [dbo].[employee_sharepoint_folders] "
                    + "(employee_profile_id, doc_kind, folder_segment, source, status, "
                    + "resolved_at, last_error) "
                    + "VALUES (?, ?, ?, 'DISCOVERED', ?, SYSDATETIMEOFFSET(), ?)",
                    employeeProfileId, kind.name(), folderSegment, status.name(), truncate(detail));

        } catch (DuplicateKeyException manualRowWins) {
            // A MANUAL row is already there, or a concurrent resolve inserted one. Both are
            // correct outcomes: the human's answer stands, and a duplicate discovery is a
            // no-op rather than an error.
            log.debug("Segment {} / {} deja fixe manuellement - decouverte ignoree",
                    employeeProfileId, kind);
        } catch (Exception e) {
            log.warn("Impossible de memoriser la resolution SharePoint {} / {}: {}",
                    employeeProfileId, kind, e.getMessage());
        }
    }

    /** An administrator pinning the folder for an employee the convention cannot predict. */
    public void saveManual(Long employeeProfileId, DocKind kind, String folderSegment) {
        if (employeeProfileId == null || kind == null) return;
        int updated = jdbc.update(
                "UPDATE [dbo].[employee_sharepoint_folders] "
                + "SET folder_segment = ?, source = 'MANUAL', status = ?, last_error = NULL, "
                + "resolved_at = SYSDATETIMEOFFSET() "
                + "WHERE employee_profile_id = ? AND doc_kind = ?",
                folderSegment, SharePointStatus.FOUND.name(), employeeProfileId, kind.name());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO [dbo].[employee_sharepoint_folders] "
                    + "(employee_profile_id, doc_kind, folder_segment, source, status, resolved_at) "
                    + "VALUES (?, ?, ?, 'MANUAL', ?, SYSDATETIMEOFFSET())",
                    employeeProfileId, kind.name(), folderSegment, SharePointStatus.FOUND.name());
        }
        log.info("Dossier SharePoint {} / {} fixe manuellement sur '{}'",
                employeeProfileId, kind, folderSegment);
    }

    /**
     * Forgets a cached resolution so the next lookup rediscovers it.
     *
     * <p>Clears MANUAL rows too: this is the operator's explicit "start over", and an override
     * that cannot be removed is a trap. The narrower "rediscover but keep my override" is what
     * a forced resolve does instead.
     */
    public void clear(Long employeeProfileId, DocKind kind) {
        if (employeeProfileId == null || kind == null) return;
        jdbc.update("DELETE FROM [dbo].[employee_sharepoint_folders] "
                    + "WHERE employee_profile_id = ? AND doc_kind = ?",
                employeeProfileId, kind.name());
    }

    private CachedFolder map(ResultSet rs) throws SQLException {
        return new CachedFolder(
                rs.getLong("employee_profile_id"),
                DocKind.from(rs.getString("doc_kind")).orElse(null),
                rs.getString("folder_segment"),
                parseSource(rs.getString("source")),
                parseStatus(rs.getString("status")),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getString("last_error"));
    }

    /**
     * A value written by a newer build than this one must not blow up a read.
     *
     * <p>Unknown source falls back to MANUAL, not DISCOVERED: the safe direction is to
     * over-protect a row from being overwritten rather than to silently clobber something a
     * human may have entered.
     */
    private ResolutionSource parseSource(String raw) {
        if (raw == null) return ResolutionSource.MANUAL;
        try {
            return ResolutionSource.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return ResolutionSource.MANUAL;
        }
    }

    private SharePointStatus parseStatus(String raw) {
        if (raw == null) return SharePointStatus.UNAVAILABLE;
        try {
            return SharePointStatus.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return SharePointStatus.UNAVAILABLE;
        }
    }

    private String truncate(String detail) {
        if (detail == null) return null;
        return detail.length() <= MAX_ERROR_LEN ? detail : detail.substring(0, MAX_ERROR_LEN);
    }
}
