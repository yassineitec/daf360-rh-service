package com.daf360.rh.service.document;

import com.daf360.rh.service.EmployeeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The document-type vocabulary for one country, from {@code document_types} (V87).
 *
 * <p>Replaces {@link EmployeeDocumentService#DOCUMENT_TYPES} as the authority on what may be
 * uploaded. That set was a Java constant that had to be edited together with
 * {@code DocumentFolderMapping} and redeployed to add a single type — and forgetting the second
 * edit filed the new type into the default folder with no error anywhere.
 *
 * <p><b>Per country, not global</b>: CNSS is Tunisian and Egypt has no equivalent, so a single
 * vocabulary meant offering Egypt types that led nowhere. The key is {@code (pays_id, code)};
 * {@code employee_documents.document_type} still stores the bare code, because the profile
 * already carries its {@code pays_id}.
 *
 * <p><b>Falls back to the Java set</b> when a country has no rows at all. rh-service has no
 * Flyway, so V87 is applied by hand: on a server that missed it, every upload would otherwise
 * be rejected as an unknown type. The fallback keeps such a server working exactly as it does
 * today — same posture as {@code SharePointLocationService}'s fallback to the legacy column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTypeService {

    /** Same 60s as the location cache. Types change from an admin form, not from traffic; the
     *  cache exists so a documents tab rendering 16 sections issues one query, not sixteen. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;

    /**
     * One selectable type.
     *
     * @param code     stored verbatim in {@code employee_documents.document_type}
     * @param labelFr  shown in the dropdown; from the DB, not an i18n key, so that adding a
     *                 type needs no frontend deploy
     * @param labelEn  may be null — the frontend falls back to {@code labelFr}, then the code
     */
    public record DocumentType(String code, String labelFr, String labelEn, int sortOrder) {}

    private record CacheEntry(List<DocumentType> types, Instant loadedAt) {}
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /** The active types for a country, in display order. Never throws, never null. */
    public List<DocumentType> forPays(Long paysId) {
        if (paysId == null) return fallback();
        CacheEntry hit = cache.get(paysId);
        if (hit != null && Duration.between(hit.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return hit.types();
        }
        List<DocumentType> loaded = load(paysId);
        cache.put(paysId, new CacheEntry(loaded, Instant.now()));
        return loaded;
    }

    /**
     * Whether this country accepts this code, case- and whitespace-insensitively.
     *
     * @return the canonical code to store, or empty when the type is not accepted
     */
    public Optional<String> validate(Long paysId, String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String code = raw.trim().toUpperCase(Locale.ROOT);
        return forPays(paysId).stream()
                .map(DocumentType::code)
                .filter(code::equals)
                .findFirst();
    }

    /** Called after any write from the admin screen, so an edit is visible immediately rather
     *  than up to {@link #CACHE_TTL} later. */
    public void evictAll() {
        cache.clear();
        requiredCache.clear();
    }

    // ── Required for a complete dossier (V121) ───────────────────────────────

    /**
     * The rule before V121, and the answer for a country RH has not configured: a contract,
     * an ID card and a RIB.
     */
    public static final List<String> DEFAULT_REQUIRED = List.of("CONTRACT", "ID_CARD", "RIB");

    private record RequiredEntry(List<String> codes, Instant loadedAt) {}
    private final Map<Long, RequiredEntry> requiredCache = new ConcurrentHashMap<>();

    /**
     * The document types that make this country's dossiers complete, in display order — chosen
     * by RH in Administration › SharePoint. Drives the « Documents manquants » card, the
     * per-employee documents bar and the nightly SharePoint sync. Never throws, never null.
     *
     * <ul>
     *   <li>V121 not applied, or a country without any {@code document_types} row →
     *       {@link #DEFAULT_REQUIRED}, so a server is never left with "nothing is required"
     *       by accident;</li>
     *   <li>a configured country where RH flagged nothing → empty: that is a choice.</li>
     * </ul>
     */
    public List<String> requiredCodes(Long paysId) {
        if (paysId == null) return DEFAULT_REQUIRED;
        RequiredEntry hit = requiredCache.get(paysId);
        if (hit != null && Duration.between(hit.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return hit.codes();
        }
        List<String> loaded = loadRequired(paysId);
        requiredCache.put(paysId, new RequiredEntry(loaded, Instant.now()));
        return loaded;
    }

    private List<String> loadRequired(Long paysId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT code, required_for_completion FROM [dbo].[document_types] " +
                    "WHERE pays_id = ? AND is_active = 1 ORDER BY sort_order, code", paysId);
            if (rows.isEmpty()) return DEFAULT_REQUIRED;
            return rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("required_for_completion")))
                    .map(r -> (String) r.get("code"))
                    .toList();
        } catch (Exception e) {
            log.debug("required_for_completion illisible ({}) — V121 non appliquee, regle par defaut",
                    e.getMessage());
            return DEFAULT_REQUIRED;
        }
    }

    /** French label of a code for this country, or the code itself. */
    public String labelFr(Long paysId, String code) {
        return forPays(paysId).stream()
                .filter(t -> t.code().equals(code))
                .map(DocumentType::labelFr)
                .findFirst()
                .orElse(code);
    }

    /**
     * Marks one type required (or not) for a complete dossier. Separate from {@link #save} so the
     * admin checklist can toggle a type without resending its labels.
     *
     * @return false when the row does not exist for this country (nothing updated)
     */
    public boolean setRequired(Long paysId, String rawCode, boolean required, Long actorId) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        int updated = jdbc.update(
                "UPDATE [dbo].[document_types] SET required_for_completion = ?, " +
                "updated_at = SYSDATETIMEOFFSET(), updated_by = ? WHERE pays_id = ? AND code = ?",
                required, actorId, paysId, code);
        evictAll();
        log.info("Type de document {} / pays {} : requis={} (utilisateur {})", code, paysId, required, actorId);
        return updated > 0;
    }

    // ── Administration ────────────────────────────────────────────────────────

    /**
     * Every row for a country, active or not, for the admin table.
     *
     * <p>Distinct from {@link #forPays}: that one hides deactivated types and falls back to the
     * Java list, both of which would be wrong here. An administrator must see a deactivated type
     * in order to reactivate it, and must not be shown a fallback list that is not in the table
     * — editing a row that does not exist would silently create one.
     */
    public List<AdminRow> listForAdmin(Long paysId) {
        try {
            return jdbc.query(
                    "SELECT id, code, label_fr, label_en, is_active, sort_order, required_for_completion " +
                    "FROM [dbo].[document_types] WHERE pays_id = ? ORDER BY sort_order, code",
                    (rs, i) -> new AdminRow(
                            rs.getLong("id"), rs.getString("code"),
                            rs.getString("label_fr"), rs.getString("label_en"),
                            rs.getBoolean("is_active"), rs.getInt("sort_order"),
                            rs.getBoolean("required_for_completion")),
                    paysId);
        } catch (org.springframework.jdbc.BadSqlGrammarException beforeV121) {
            // Column absent: list the types, none flagged — the screen still works and the
            // checklist simply cannot be saved until V121 is applied.
            return jdbc.query(
                    "SELECT id, code, label_fr, label_en, is_active, sort_order " +
                    "FROM [dbo].[document_types] WHERE pays_id = ? ORDER BY sort_order, code",
                    (rs, i) -> new AdminRow(
                            rs.getLong("id"), rs.getString("code"),
                            rs.getString("label_fr"), rs.getString("label_en"),
                            rs.getBoolean("is_active"), rs.getInt("sort_order"), false),
                    paysId);
        }
    }

    /** One row as the admin screen sees it, including deactivated ones. */
    public record AdminRow(Long id, String code, String labelFr, String labelEn,
                           boolean active, int sortOrder, boolean requiredForCompletion) {}

    /**
     * Creates or updates one type for one country.
     *
     * <p>The code is normalised and constrained to {@code A-Z 0-9 _}: it becomes a
     * {@code doc_kind} value in {@code sharepoint_locations} and a stored
     * {@code employee_documents.document_type}, so a code with a space or a slash would be a
     * value two other tables cannot round-trip.
     *
     * @return validation problem keys; empty means saved
     */
    public List<String> save(Long paysId, String rawCode, String labelFr, String labelEn,
                             boolean active, Integer sortOrder, Long actorId) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (code.isBlank())                       return List.of("DOC_TYPE.CODE.BLANK");
        if (!code.matches("[A-Z0-9_]{1,100}"))    return List.of("DOC_TYPE.CODE.INVALID");
        if (labelFr == null || labelFr.isBlank()) return List.of("DOC_TYPE.LABEL.BLANK");

        int order = sortOrder == null ? 100 : sortOrder;
        int updated = jdbc.update(
                "UPDATE [dbo].[document_types] SET label_fr = ?, label_en = ?, is_active = ?, " +
                "sort_order = ?, updated_at = SYSDATETIMEOFFSET(), updated_by = ? " +
                "WHERE pays_id = ? AND code = ?",
                labelFr.trim(), labelEn, active, order, actorId, paysId, code);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO [dbo].[document_types] " +
                    "(pays_id, code, label_fr, label_en, is_active, sort_order, updated_at, updated_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, SYSDATETIMEOFFSET(), ?)",
                    paysId, code, labelFr.trim(), labelEn, active, order, actorId);
        }
        evictAll();
        log.info("Type de document {} / pays {} enregistre par l utilisateur {} (actif={})",
                code, paysId, actorId, active);
        return List.of();
    }

    /**
     * Deactivates a type. Never deletes.
     *
     * <p>{@code employee_documents.document_type} holds the code on rows already filed, and it
     * carries no foreign key — so a DELETE would leave documents labelled with a code nothing
     * can resolve, and the row would silently fail {@link #validate} on the next metadata edit.
     * Deactivating removes it from the dropdown while every existing document stays readable and
     * its SharePoint path stays resolvable.
     */
    public void deactivate(Long id, Long actorId) {
        jdbc.update("UPDATE [dbo].[document_types] SET is_active = 0, " +
                    "updated_at = SYSDATETIMEOFFSET(), updated_by = ? WHERE id = ?",
                actorId, id);
        evictAll();
        log.info("Type de document {} desactive par l utilisateur {}", id, actorId);
    }

    private List<DocumentType> load(Long paysId) {
        try {
            List<DocumentType> rows = jdbc.query(
                    "SELECT code, label_fr, label_en, sort_order " +
                    "FROM [dbo].[document_types] " +
                    "WHERE pays_id = ? AND is_active = 1 " +
                    "ORDER BY sort_order, code",
                    (rs, i) -> new DocumentType(
                            rs.getString("code"),
                            rs.getString("label_fr"),
                            rs.getString("label_en"),
                            rs.getInt("sort_order")),
                    paysId);
            // No rows is NOT an error to report: it is either a country nobody has configured
            // or a server without V87, and both must keep accepting uploads.
            if (rows.isEmpty()) {
                log.debug("Aucun type de document pour le pays {} — repli sur la liste Java", paysId);
                return fallback();
            }
            return rows;
        } catch (Exception e) {
            // Most likely cause: V87 not applied here. Debug, not warn — the fallback covers it
            // and warning on every dropdown render would bury the log.
            log.debug("Lecture de document_types impossible ({}), repli sur la liste Java",
                    e.getMessage());
            return fallback();
        }
    }

    /**
     * The pre-V87 vocabulary, labelled by its own code.
     *
     * <p>Labels are the raw codes on purpose: inventing French labels here would duplicate
     * fr.json and drift from it. The frontend already falls back to the code, and this path
     * only runs on a server that has not been migrated.
     */
    private List<DocumentType> fallback() {
        List<String> codes = List.copyOf(EmployeeDocumentService.DOCUMENT_TYPES);
        return codes.stream()
                .sorted()
                .map(c -> new DocumentType(c, c, c, 100))
                .toList();
    }
}
