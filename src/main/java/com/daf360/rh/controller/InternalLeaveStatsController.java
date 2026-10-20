package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Approved congé and maladie days per employee per month, for the timesheet's
 * « Statistiques RH » page.
 *
 * That page stays in the timesheet (decision of 2026-09-30), but the leave it counts is RH's
 * now. It used to aggregate the timesheet's own `absences` copy, which stops changing the day
 * congés are filed in CORE360 — so it would have shown stale numbers forever. This is the
 * same aggregation over DAF360_HR.
 *
 * Keys are business keys only — country ISO code and e-mail — never ids: neither user ids nor
 * pays ids line up between the two databases (TIMESHEET-INTEGRATION-DECISIONS.md, C0.5).
 *
 * Country = the one FROZEN on the request (absences.pays_id), not the employee's current one:
 * a country's report must not lose the leave of someone who has since transferred.
 *
 * Internal (X-Internal-Key): the timesheet checks GET_HR_STATS before it calls.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalLeaveStatsController {

    private static final String HEADER = "X-Internal-Key";

    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    public record LeaveStatRow(String email, String fullName, int month, int year, String type, BigDecimal days) {}

    /**
     * GET /api/hr/internal/leave-stats?paysIso=TN[&email=][&month=][&year=]
     * Rows ordered by year, month, name — the order the page and its export show.
     */
    @GetMapping("/api/hr/internal/leave-stats")
    public ResponseEntity<List<LeaveStatRow>> leaveStats(
            @RequestParam String paysIso,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);

        // Optional filters are appended, not written as `? IS NULL OR …`: the SQL Server driver
        // cannot always type an untyped null parameter, and fails at runtime when it cannot.
        StringBuilder sql = new StringBuilder("""
                SELECT u.email, u.fullName,
                       MONTH(a.date_debut) AS m, YEAR(a.date_debut) AS y,
                       a.type, SUM(a.total_jours) AS days
                FROM   [dbo].[absences] a
                JOIN   [dbo].[Users] u ON u.id = a.collaborateur_id
                JOIN   [dbo].[pays]  p ON p.id = a.pays_id
                WHERE  p.iso_code = ?
                  AND  a.etat_demande = 'VALIDE'
                  AND  a.type IN ('CONGE', 'MALADIE')
                """);
        List<Object> params = new ArrayList<>(List.of(paysIso));
        if (email != null && !email.isBlank()) {
            sql.append(" AND LOWER(LTRIM(RTRIM(u.email))) = LOWER(LTRIM(RTRIM(?)))");
            params.add(email);
        }
        if (month != null) { sql.append(" AND MONTH(a.date_debut) = ?"); params.add(month); }
        if (year  != null) { sql.append(" AND YEAR(a.date_debut) = ?");  params.add(year); }
        sql.append(" GROUP BY u.email, u.fullName, MONTH(a.date_debut), YEAR(a.date_debut), a.type")
           .append(" ORDER BY y, m, u.fullName");

        List<LeaveStatRow> rows = jdbcTemplate.query(sql.toString(),
                (rs, i) -> new LeaveStatRow(rs.getString("email"), rs.getString("fullName"),
                        rs.getInt("m"), rs.getInt("y"), rs.getString("type"), rs.getBigDecimal("days")),
                params.toArray());

        return ResponseEntity.ok(rows);
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        byte[] x = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = provided == null ? new byte[0] : provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(x, y)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }
}
