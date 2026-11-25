package com.daf360.rh.repository;

import com.daf360.rh.dto.AuditEntryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The audit screen's filtered, server-paged read of the merged audit_log + audit_trail
 * timeline (Administration → Journal d'audit).
 *
 * <p>Plain JDBC rather than a Spring Data native query: every filter is optional, and the
 * count must apply exactly the same WHERE as the page — a fixed countQuery cannot.
 * {@link AuditQueryRepository} stays as it is for its other caller (candidate history).
 *
 * <p>Unlike AuditQueryRepository, GENERIC rows keep their raw I/U/D action code — what the
 * screen colours and translates (AUDIT.ACTION_I/U/D).
 *
 * <p><b>Country.</b> audit_log rows carry pays_id. An audit_trail row has none of its own: its
 * country is the row's own {@code pays_id} column when the table has one, else the pays of the
 * employee it belongs to ({@code employee_profile_id}, or the id itself for employee_profiles).
 * Rows with no resolvable country (roles, users, company-wide catalogues) belong to every
 * entity, so a country filter keeps them. That resolution parses JSON per row, so it is only
 * added to the query when a country filter actually applies.
 */
@Repository
@RequiredArgsConstructor
public class AuditSearchRepository {

    /** {@code actor} value meaning « no user » (scheduled job, ad-hoc SQL). */
    public static final String SYSTEM_ACTOR = "__SYSTEM__";

    private final NamedParameterJdbcTemplate jdbc;

    public record Criteria(
            String q,
            LocalDate from,
            LocalDate to,
            String source,
            Collection<String> actions,
            Collection<String> entityTypes,
            String actor,
            /** null = no country filter (whole perimeter). */
            Collection<Long> paysIds
    ) {}

    public Page<AuditEntryDto> search(Criteria c, int page, int size) {
        boolean byPays = c.paysIds() != null && !c.paysIds().isEmpty();
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = where(c, p);
        String merged = merged(byPays);

        Long total = jdbc.queryForObject(
                "WITH merged AS (" + merged + ") SELECT COUNT_BIG(*) FROM merged" + where, p, Long.class);

        p.addValue("offset", (long) page * size).addValue("size", size);
        List<AuditEntryDto> content = jdbc.query(
                "WITH merged AS (" + merged + ") SELECT * FROM merged" + where
                        + " ORDER BY ts DESC, source, id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                p,
                (rs, n) -> new AuditEntryDto(
                        rs.getLong("id"), rs.getString("source"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("entityType"), rs.getString("entityId"), rs.getString("oldValue"),
                        rs.getString("newValue"), rs.getString("ip"), rs.getObject("ts", LocalDateTime.class)));

        return new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0 : total);
    }

    /** What the filter selects offer: every element, actor and event action ever logged. */
    public List<String> distinctEntityTypes() {
        return jdbc.getJdbcTemplate().queryForList("""
                SELECT v FROM (
                    SELECT CAST(entityType AS NVARCHAR(100)) AS v FROM audit_log
                    UNION SELECT CAST(table_name AS NVARCHAR(100)) FROM audit_trail
                ) x WHERE v IS NOT NULL ORDER BY v
                """, String.class);
    }

    public List<String> distinctActors() {
        return jdbc.getJdbcTemplate().queryForList("""
                SELECT v FROM (
                    SELECT CAST(userId AS NVARCHAR(200)) AS v FROM audit_log
                    UNION SELECT CAST(changed_by AS NVARCHAR(200)) FROM audit_trail
                ) x WHERE v IS NOT NULL AND LTRIM(v) <> '' ORDER BY v
                """, String.class);
    }

    public List<String> distinctEventActions() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT CAST(action AS NVARCHAR(50)) AS v FROM audit_log WHERE action IS NOT NULL ORDER BY v",
                String.class);
    }

    // ── SQL ─────────────────────────────────────────────────────────────────────

    private static String merged(boolean withPays) {
        String eventPays = withPays ? "CAST(a.pays_id AS BIGINT)" : "CAST(NULL AS BIGINT)";
        String genericPays = withPays
                ? """
                  COALESCE(TRY_CAST(JSON_VALUE(r.j, '$.pays_id') AS BIGINT),
                           (SELECT ep.pays_id FROM employee_profiles ep
                             WHERE ep.id = CASE WHEN t.table_name = N'employee_profiles'
                                                THEN TRY_CAST(JSON_VALUE(r.pk, '$.id') AS BIGINT)
                                                ELSE TRY_CAST(JSON_VALUE(r.j, '$.employee_profile_id') AS BIGINT) END))
                  """
                : "CAST(NULL AS BIGINT)";
        String genericApply = withPays
                ? """
                  CROSS APPLY (SELECT
                      CASE WHEN ISJSON(COALESCE(t.new_value, t.old_value)) = 1 THEN COALESCE(t.new_value, t.old_value) END AS j,
                      CASE WHEN ISJSON(t.pk_json) = 1 THEN t.pk_json END AS pk) r
                  """
                : "";
        return """
                SELECT a.id,
                       CAST('EVENT_LOG' AS VARCHAR(10)) AS source,
                       CAST(a.userId AS NVARCHAR(200)) AS actor,
                       CAST(a.action AS NVARCHAR(50)) AS action,
                       CAST(a.entityType AS NVARCHAR(100)) AS entityType,
                       CAST(a.entityId AS NVARCHAR(400)) AS entityId,
                       CAST(a.oldValue AS NVARCHAR(MAX)) AS oldValue,
                       CAST(a.newValue AS NVARCHAR(MAX)) AS newValue,
                       CAST(a.ipAddress AS NVARCHAR(50)) AS ip,
                       CAST(SWITCHOFFSET(a.[timestamp], '+00:00') AS DATETIME2) AS ts,
                       %s AS paysId
                FROM audit_log a
                UNION ALL
                SELECT t.id,
                       CAST('GENERIC' AS VARCHAR(10)),
                       CAST(t.changed_by AS NVARCHAR(200)),
                       CAST(t.action AS NVARCHAR(50)),
                       CAST(t.table_name AS NVARCHAR(100)),
                       CAST(t.pk_json AS NVARCHAR(400)),
                       CAST(t.old_value AS NVARCHAR(MAX)),
                       CAST(t.new_value AS NVARCHAR(MAX)),
                       CAST(NULL AS NVARCHAR(50)),
                       t.changed_at,
                       %s
                FROM audit_trail t
                %s
                """.formatted(eventPays, genericPays, genericApply);
    }

    private static String where(Criteria c, MapSqlParameterSource p) {
        List<String> w = new ArrayList<>();

        if (c.q() != null && !c.q().isBlank()) {
            p.addValue("q", "%" + escapeLike(c.q().trim()) + "%");
            w.add("""
                  (actor LIKE :q ESCAPE '\\' OR action LIKE :q ESCAPE '\\' OR entityType LIKE :q ESCAPE '\\'
                   OR entityId LIKE :q ESCAPE '\\' OR ip LIKE :q ESCAPE '\\'
                   OR oldValue LIKE :q ESCAPE '\\' OR newValue LIKE :q ESCAPE '\\'
                   OR actor IN (SELECT CAST(u.id AS NVARCHAR(200)) FROM Users u
                                 WHERE u.fullName LIKE :q ESCAPE '\\' OR u.email LIKE :q ESCAPE '\\'))""");
        }
        // ts is UTC; the day bounds are the caller's calendar days, close enough for a log.
        if (c.from() != null) {
            p.addValue("from", c.from().atStartOfDay());
            w.add("ts >= :from");
        }
        if (c.to() != null) {
            p.addValue("toExcl", c.to().plusDays(1).atStartOfDay());
            w.add("ts < :toExcl");
        }
        if (c.source() != null && !c.source().isBlank()) {
            p.addValue("source", c.source());
            w.add("source = :source");
        }
        if (c.actions() != null && !c.actions().isEmpty()) {
            p.addValue("actions", c.actions());
            w.add("action IN (:actions)");
        }
        if (c.entityTypes() != null && !c.entityTypes().isEmpty()) {
            p.addValue("entityTypes", c.entityTypes());
            w.add("entityType IN (:entityTypes)");
        }
        if (c.actor() != null && !c.actor().isBlank()) {
            if (SYSTEM_ACTOR.equals(c.actor())) {
                w.add("(actor IS NULL OR LTRIM(actor) = '')");
            } else {
                p.addValue("actor", c.actor());
                w.add("actor = :actor");
            }
        }
        if (c.paysIds() != null && !c.paysIds().isEmpty()) {
            p.addValue("paysIds", c.paysIds());
            w.add("(paysId IS NULL OR paysId IN (:paysIds))");
        }
        return w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
    }
}
