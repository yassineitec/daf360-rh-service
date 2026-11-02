package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.service.WorkingDayCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Absence totals per employee over a period, for the timesheet's recap dashboard (its KPI
 * cards and absence donut). The timesheet used to sum its own copy of the leave tables;
 * congés and autorisations belong to RH now, so this is where it reads them.
 *
 * Only APPROVED requests count. A congé overlapping the period counts the working days of the
 * overlap (the employee's entity weekend and holidays excluded; a half-day is half a day).
 * Sick days are the medical types — the split the old dashboard drew.
 *
 * Internal (X-Internal-Key), like the working calendar: the timesheet has already checked that
 * the caller may see these people (themselves, or their team). Ids are RH user ids — the
 * timesheet keys its users on them since its users_ref switch.
 */
@RestController
@RequiredArgsConstructor
public class InternalAbsenceSummaryController {

    private static final String HEADER = "X-Internal-Key";
    /** A recap covers at most a few years; this only stops a runaway range. */
    private static final int MAX_DAYS = 3 * 366;
    private static final int MAX_USERS = 500;
    private static final Set<String> SICK_TYPES = Set.of("MALADIE", "MATERNITE", "PATERNITE");

    private final WorkingDayCalculator calendar;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    public record AbsenceSummary(Long userId, BigDecimal absenceDays, BigDecimal sickDays,
                                 BigDecimal autorisationHours) {}

    /**
     * GET /api/hr/internal/absence-summary?userIds=1,2&from=YYYY-MM-DD&to=YYYY-MM-DD
     * One row per requested id, zeros included, in the order asked.
     */
    @GetMapping("/api/hr/internal/absence-summary")
    public List<AbsenceSummary> absenceSummary(
            @RequestParam List<Long> userIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from/to must be ordered and at most " + MAX_DAYS + " days apart");
        }
        List<Long> ids = userIds.stream().distinct().toList();
        if (ids.isEmpty()) return List.of();
        if (ids.size() > MAX_USERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at most " + MAX_USERS + " users");
        }

        Map<Long, BigDecimal[]> totals = new LinkedHashMap<>();
        ids.forEach(id -> totals.put(id, new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO }));
        String in = ids.stream().map(i -> "?").collect(Collectors.joining(","));

        jdbcTemplate.query("""
                SELECT a.collaborateur_id, a.pays_id, a.type, a.category, a.date_debut, a.date_fin
                FROM   [dbo].[absences] a
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.collaborateur_id IN (%s)
                  AND  a.date_debut <= ? AND a.date_fin >= ?
                """.formatted(in), rs -> {
            long uid = rs.getLong("collaborateur_id");
            Number pays = (Number) rs.getObject("pays_id");
            LocalDate start = rs.getDate("date_debut").toLocalDate();
            LocalDate end = rs.getDate("date_fin").toLocalDate();
            LocalDate s = start.isBefore(from) ? from : start;
            LocalDate e = end.isAfter(to) ? to : end;
            BigDecimal days = calendar.billableDays(s, e, LeaveCategory.valueOf(rs.getString("category")),
                    pays == null ? null : pays.longValue());
            boolean sick = SICK_TYPES.contains(rs.getString("type"));
            BigDecimal[] t = totals.get(uid);
            if (sick) t[1] = t[1].add(days); else t[0] = t[0].add(days);
        }, params(ids, to, from));

        jdbcTemplate.query("""
                SELECT a.collaborateur_id, SUM(a.duree_minutes) AS minutes
                FROM   [dbo].[autorisations] a
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.collaborateur_id IN (%s)
                  AND  a.date_autorisation BETWEEN ? AND ?
                GROUP BY a.collaborateur_id
                """.formatted(in), rs -> {
            long uid = rs.getLong("collaborateur_id");
            totals.get(uid)[2] = BigDecimal.valueOf(rs.getLong("minutes"))
                    .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
        }, params(ids, from, to));

        return totals.entrySet().stream()
                .map(e -> new AbsenceSummary(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    /** The ids for the IN list, then the two dates. */
    private static Object[] params(List<Long> ids, Object a, Object b) {
        Object[] out = new Object[ids.size() + 2];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        out[ids.size()] = a;
        out[ids.size() + 1] = b;
        return out;
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        byte[] x = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = provided == null ? new byte[0] : provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(x, y)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }
}
