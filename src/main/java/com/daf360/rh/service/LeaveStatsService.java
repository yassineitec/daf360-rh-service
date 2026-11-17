package com.daf360.rh.service;

import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * « Statistiques des absences » (/rh/absences/statistiques): approved CONGE and MALADIE days
 * per employee and month. Moved here from the timesheet, which only relayed these numbers
 * from RH (through an internal endpoint, now removed) and translated ids on the way.
 *
 * Same counting rule as that endpoint: VALIDE requests only, counted in the month they
 * START, in the country FROZEN on the request (absences.pays_id) — a country's report keeps
 * the leave of someone who has since transferred.
 *
 * The caller's role scope (PaysScopeContext, V74) is a ceiling, exactly as on the global
 * leave list; `paysId` is the screen's own filter on top of it.
 *
 * The rows are aggregated in SQL per employee + month + type, then folded into one row per
 * employee + month here, where the search, the multi-value filters, the sort, the paging and
 * the KPI totals are applied — the result is small (employees × months), unlike the requests.
 */
@Service
@RequiredArgsConstructor
public class LeaveStatsService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantService tenantService;

    /** One employee's approved days in one month. */
    public record StatRow(Long collaborateurId, String collaborateurName, Long collaborateurProfileId,
                          String collaborateurPhotoUrl, String collaborateurGender,
                          int month, int year, double totalConge, double totalMaladie, double total) {}

    /** The KPI row: totals over every row the filters match, not only the page. */
    public record Summary(long collaborateurs, double totalConge, double totalMaladie, double total) {}

    /** The filter panel's choices: only what occurs in the caller's statistics. */
    public record Person(Long id, String name) {}
    public record FilterOptions(List<Person> collaborateurs, List<Integer> months, List<Integer> years) {}

    public record Filters(Long paysId, String keyword, List<Long> collaborateurs, List<Integer> months,
                          List<Integer> years) {
        public static Filters none(Long paysId) {
            return new Filters(paysId, null, null, null, null);
        }
    }

    public record Result(List<StatRow> page, long totalElements, Summary summary) {}

    @Transactional(readOnly = true)
    public Result page(Filters filters, String sort, String dir, int page, int size) {
        List<StatRow> all = sorted(rows(filters), sort, dir);
        int from = Math.min(Math.max(page, 0) * Math.max(size, 1), all.size());
        int to = Math.min(from + Math.max(size, 1), all.size());
        return new Result(List.copyOf(all.subList(from, to)), all.size(), summaryOf(all));
    }

    /** Every matching row, sorted — the export. */
    @Transactional(readOnly = true)
    public List<StatRow> all(Filters filters, String sort, String dir) {
        return sorted(rows(filters), sort, dir);
    }

    @Transactional(readOnly = true)
    public FilterOptions filterOptions(Long paysId) {
        List<StatRow> all = rows(Filters.none(paysId));
        Map<Long, Person> people = new LinkedHashMap<>();
        all.stream()
                .sorted(Comparator.comparing(r -> nullToEmpty(r.collaborateurName()), String.CASE_INSENSITIVE_ORDER))
                .forEach(r -> people.putIfAbsent(r.collaborateurId(), new Person(r.collaborateurId(), r.collaborateurName())));
        List<Integer> months = all.stream().map(StatRow::month).distinct().sorted().toList();
        List<Integer> years = all.stream().map(StatRow::year).distinct().sorted(Comparator.reverseOrder()).toList();
        return new FilterOptions(List.copyOf(people.values()), months, years);
    }

    // ── Query ────────────────────────────────────────────────────────────────

    private List<StatRow> rows(Filters f) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();

        // Optional filters are appended, not written as `? IS NULL OR …`: the SQL Server
        // driver cannot always type an untyped null parameter (same rule as the internal
        // endpoint this replaces).
        StringBuilder sql = new StringBuilder("""
                SELECT a.collaborateur_id AS uid,
                       MONTH(a.date_debut) AS m, YEAR(a.date_debut) AS y,
                       a.type, SUM(a.total_jours) AS days
                FROM   [dbo].[absences] a
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.type IN ('CONGE', 'MALADIE')
                """);
        List<Object> params = new ArrayList<>();
        if (!scope.unfiltered()) {
            List<Long> ids = scope.idsOrPlaceholder();
            sql.append(" AND a.pays_id IN (").append(placeholders(ids.size())).append(')');
            params.addAll(ids);
        }
        if (f.paysId() != null) { sql.append(" AND a.pays_id = ?"); params.add(f.paysId()); }
        appendIn(sql, params, "a.collaborateur_id", f.collaborateurs());
        appendIn(sql, params, "MONTH(a.date_debut)", f.months());
        appendIn(sql, params, "YEAR(a.date_debut)", f.years());
        sql.append(" GROUP BY a.collaborateur_id, MONTH(a.date_debut), YEAR(a.date_debut), a.type");

        record Raw(long uid, int month, int year, String type, BigDecimal days) {}
        List<Raw> raw = jdbcTemplate.query(sql.toString(),
                (rs, i) -> new Raw(rs.getLong("uid"), rs.getInt("m"), rs.getInt("y"), rs.getString("type"),
                        rs.getBigDecimal("days")),
                params.toArray());
        if (raw.isEmpty()) return new ArrayList<>();

        Set<Long> uids = raw.stream().map(Raw::uid).collect(Collectors.toSet());
        Map<Long, String> names = namesFor(uids);
        Map<Long, Face> faces = facesFor(uids);

        // Fold CONGE and MALADIE into one row per employee + month + year.
        Map<String, double[]> days = new LinkedHashMap<>();
        Map<String, Raw> keys = new LinkedHashMap<>();
        for (Raw r : raw) {
            String key = r.uid() + "-" + r.year() + "-" + r.month();
            keys.putIfAbsent(key, r);
            double[] d = days.computeIfAbsent(key, k -> new double[2]);
            double v = r.days() == null ? 0 : r.days().doubleValue();
            if ("CONGE".equals(r.type())) d[0] += v;
            else if ("MALADIE".equals(r.type())) d[1] += v;
        }

        String keyword = f.keyword() == null ? "" : f.keyword().trim().toLowerCase(Locale.ROOT);
        List<StatRow> out = new ArrayList<>(keys.size());
        for (Map.Entry<String, Raw> e : keys.entrySet()) {
            Raw r = e.getValue();
            double[] d = days.get(e.getKey());
            String name = names.get(r.uid());
            if (!keyword.isEmpty() && !nullToEmpty(name).toLowerCase(Locale.ROOT).contains(keyword)) continue;
            Face face = faces.getOrDefault(r.uid(), new Face(null, null, null));
            out.add(new StatRow(r.uid(), name, face.profileId(), face.photoUrl(), face.gender(),
                    r.month(), r.year(), d[0], d[1], d[0] + d[1]));
        }
        return out;
    }

    /**
     * The table's sort keys; without one, newest period first. `periode` sorts by year then
     * month. The name always breaks ties, so a page never reshuffles between two loads.
     */
    private static List<StatRow> sorted(List<StatRow> rows, String sort, String dir) {
        Comparator<StatRow> byName = Comparator.comparing(r -> nullToEmpty(r.collaborateurName()),
                String.CASE_INSENSITIVE_ORDER);
        Comparator<StatRow> byPeriod = Comparator.comparingInt(StatRow::year).thenComparingInt(StatRow::month);
        Comparator<StatRow> c = switch (sort == null ? "" : sort) {
            case "collaborateur" -> byName;
            case "periode" -> byPeriod;
            case "totalConge" -> Comparator.comparingDouble(StatRow::totalConge);
            case "totalMaladie" -> Comparator.comparingDouble(StatRow::totalMaladie);
            case "total" -> Comparator.comparingDouble(StatRow::total);
            default -> null;
        };
        if (c == null) {
            c = byPeriod.reversed();
        } else if ("desc".equalsIgnoreCase(dir)) {
            c = c.reversed();
        }
        rows.sort(c.thenComparing(byName));
        return rows;
    }

    private static Summary summaryOf(List<StatRow> rows) {
        long people = rows.stream().map(StatRow::collaborateurId).filter(Objects::nonNull).distinct().count();
        double conge = rows.stream().mapToDouble(StatRow::totalConge).sum();
        double maladie = rows.stream().mapToDouble(StatRow::totalMaladie).sum();
        return new Summary(people, conge, maladie, conge + maladie);
    }

    // ── Names and faces, batched (same lookups as LeaveRequestMapper) ────────

    private record Face(Long profileId, String photoUrl, String gender) {}

    /** Ids come from the database, never from request input, so the IN list is safe. */
    private Map<Long, String> namesFor(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>(ids.size());
        jdbcTemplate.query("SELECT id, fullName FROM Users WHERE id IN (" + inList(ids) + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("fullName")); });
        return out;
    }

    /** A user without an HR file has no profile: they render as initials. */
    private Map<Long, Face> facesFor(Set<Long> ids) {
        Map<Long, Face> out = new HashMap<>(ids.size());
        jdbcTemplate.query("SELECT user_id, id, photo_url, gender FROM employee_profiles WHERE user_id IN ("
                        + inList(ids) + ")",
                rs -> { out.put(rs.getLong("user_id"),
                        new Face(rs.getLong("id"), rs.getString("photo_url"), rs.getString("gender"))); });
        return out;
    }

    private static String inList(Set<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static void appendIn(StringBuilder sql, List<Object> params, String column, List<?> values) {
        if (values == null || values.isEmpty()) return;
        sql.append(" AND ").append(column).append(" IN (").append(placeholders(values.size())).append(')');
        params.addAll(values);
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
