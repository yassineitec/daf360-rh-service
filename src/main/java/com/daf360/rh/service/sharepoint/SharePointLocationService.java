package com.daf360.rh.service.sharepoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the configured path template for a (country, document kind), from
 * {@code sharepoint_locations} (V84).
 *
 * <p>Falls back to {@code pays.photo_sharepoint_location} for {@link DocKind#PHOTO} when no
 * row exists. That fallback is temporary — one release — and exists because rh-service has no
 * Flyway: V84 is applied by hand, so a server where it was missed must keep serving avatars
 * from the old column rather than lose them. Remove the fallback once every environment is
 * confirmed migrated, and drop the column with it.
 *
 * <p>A template that fails {@link DocKind#validateTemplate} is treated as absent. Building a
 * path from a template known to be malformed would produce a 404 at request time and an
 * operator hunting for a missing folder, when the actual fault is one bad row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointLocationService {

    /** Config changes come from an admin form, not from traffic, so a short TTL is plenty —
     *  its real job is to keep a 130-employee batch resolve from issuing 130 identical
     *  queries, not to hide a slow table. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;

    private record CacheEntry(Optional<String> template, Instant loadedAt) {}
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** One configured location, for the admin panel's table. */
    public record LocationRow(Long id, Long paysId, String isoCode, DocKind docKind,
                              String pathTemplate, boolean active) {}

    /**
     * The template for this country and kind, or empty when nothing usable is configured.
     * Never throws — a database error degrades to "not configured", same as everything else
     * in this package.
     */
    public Optional<String> templateFor(Long paysId, DocKind kind) {
        if (paysId == null || kind == null) return Optional.empty();
        String key = paysId + "|" + kind.name();
        CacheEntry hit = cache.get(key);
        if (hit != null && Duration.between(hit.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return hit.template();
        }
        Optional<String> loaded = load(paysId, kind);
        cache.put(key, new CacheEntry(loaded, Instant.now()));
        return loaded;
    }

    /** Called by the admin panel after any write, so an edit is visible immediately rather
     *  than up to {@link #CACHE_TTL} later — a stale path is exactly the kind of confusion
     *  this whole phase exists to remove. */
    public void evictAll() {
        cache.clear();
    }

    public List<LocationRow> listAll() {
        return jdbc.query(
                "SELECT l.id, l.pays_id, p.iso_code, l.doc_kind, l.path_template, l.is_active " +
                "FROM [dbo].[sharepoint_locations] l " +
                "JOIN [dbo].[pays] p ON p.id = l.pays_id " +
                "ORDER BY p.iso_code, l.doc_kind",
                (rs, i) -> {
                    // An unknown doc_kind is a row prepared for a build that is not deployed
                    // here. Listing it as null lets the admin see it rather than wonder why
                    // their insert vanished.
                    DocKind kind = DocKind.from(rs.getString("doc_kind")).orElse(null);
                    return new LocationRow(
                            rs.getLong("id"),
                            rs.getLong("pays_id"),
                            rs.getString("iso_code"),
                            kind,
                            rs.getString("path_template"),
                            rs.getBoolean("is_active"));
                });
    }

    private Optional<String> load(Long paysId, DocKind kind) {
        String template = null;
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT path_template FROM [dbo].[sharepoint_locations] " +
                    "WHERE pays_id = ? AND doc_kind = ? AND is_active = 1",
                    String.class, paysId, kind.name());
            if (!rows.isEmpty()) template = rows.get(0);
        } catch (Exception e) {
            // Most likely cause: V84 not applied on this server. Debug, not warn — the
            // fallback below covers the case that matters, and warning on every lookup
            // would bury the log.
            log.debug("Lecture de sharepoint_locations impossible ({}), repli sur la " +
                      "configuration historique", e.getMessage());
        }

        if (template == null && kind == DocKind.PHOTO) {
            template = legacyPhotoTemplate(paysId);
            if (template != null) {
                log.debug("PHOTO pays {} resolu depuis pays.photo_sharepoint_location " +
                          "(pas de ligne sharepoint_locations)", paysId);
            }
        }
        if (template == null || template.isBlank()) return Optional.empty();

        List<String> problems = kind.validateTemplate(template);
        if (!problems.isEmpty()) {
            log.warn("Chemin SharePoint invalide pour pays {} / {} : '{}' — {}",
                    paysId, kind, template, String.join(", ", problems));
            return Optional.empty();
        }
        return Optional.of(template.trim());
    }

    /**
     * The pre-V84 home of the PHOTO path. Kept readable so a deployment that has not applied
     * V84 keeps working; see the class javadoc for when to delete this.
     */
    private String legacyPhotoTemplate(Long paysId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT photo_sharepoint_location FROM [dbo].[pays] WHERE id = ?",
                    String.class, paysId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** Kinds configured for a country, for the admin panel's "what is set up here" view. */
    public List<DocKind> configuredKinds(Long paysId) {
        List<DocKind> kinds = new ArrayList<>();
        for (DocKind kind : DocKind.values()) {
            if (templateFor(paysId, kind).isPresent()) kinds.add(kind);
        }
        return kinds;
    }

    /** Normalises a kind coming off a query string, for controllers. */
    public Optional<DocKind> parseKind(String raw) {
        return DocKind.from(raw == null ? null : raw.trim().toUpperCase(Locale.ROOT));
    }
}
