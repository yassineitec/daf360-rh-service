package com.daf360.rh.history;

import com.daf360.rh.security.PaysScopeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What every module's « Historique » needs: the period window, the entity-scope filter, the
 * actor and employee names, and the reading of the action log's free-form values.
 */
@Component
@RequiredArgsConstructor
public class HistorySupport {

    /** No period chosen: this far back. */
    public static final int DEFAULT_WINDOW_DAYS = 365;
    /** Never more lines than this in one response (newest kept). */
    public static final int MAX_ROWS = 3000;

    private final JdbcTemplate jdbc;

    /** UTC [from, to) bounds of a read. */
    public record Window(LocalDateTime from, LocalDateTime to) {
        public boolean contains(LocalDateTime at) {
            return at != null && !at.isBefore(from) && at.isBefore(to);
        }
    }

    /**
     * [from, to] are the user's calendar days (both included). Widened by a day on each side:
     * the server does not know the user's time zone, the screen trims to the exact days.
     * No period = the last {@value #DEFAULT_WINDOW_DAYS} days.
     */
    public static Window window(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(ZoneOffset.UTC).minusDays(DEFAULT_WINDOW_DAYS);
        LocalDate end   = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        if (end.isBefore(start)) { LocalDate t = start; start = end; end = t; }
        return new Window(start.minusDays(1).atStartOfDay(), end.plusDays(2).atStartOfDay());
    }

    /** {@code " AND col IN (…)"}, {@code " AND 1 = 0"} or nothing — the caller's entity scope. */
    public static String paysFilter(PaysScopeContext.Scope scope, String column) {
        if (scope.unfiltered()) return "";
        if (scope.paysIds().isEmpty()) return " AND 1 = 0";
        return " AND " + column + " IN (" + scope.paysIds().stream()
                .map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }

    // ── Names ────────────────────────────────────────────────────────────────

    public Map<Long, String> userNames(Collection<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (String in : chunks(ids)) {
            jdbc.query("SELECT id, COALESCE(fullName, username, email) AS name FROM [dbo].[Users] WHERE id IN (" + in + ")",
                    rs -> { out.put(rs.getLong("id"), rs.getString("name")); });
        }
        return out;
    }

    public record EmployeeRef(String name, String gender, String photoUrl) {}

    /** Display name (the user's), gender and photo of employee profiles. */
    public Map<Long, EmployeeRef> employees(Collection<Long> profileIds) {
        Map<Long, EmployeeRef> out = new HashMap<>();
        for (String in : chunks(profileIds)) {
            jdbc.query("""
                    SELECT ep.id, COALESCE(u.fullName, u.username, u.email) AS name, ep.gender, ep.photo_url
                    FROM [dbo].[employee_profiles] ep
                    LEFT JOIN [dbo].[Users] u ON u.id = ep.user_id
                    WHERE ep.id IN (%s)
                    """.formatted(in),
                    rs -> { out.put(rs.getLong("id"),
                            new EmployeeRef(rs.getString("name"), rs.getString("gender"), rs.getString("photo_url"))); });
        }
        return out;
    }

    /** Inlined Long lists, 1 000 at a time — SQL Server caps a statement at 2 100 parameters. */
    private static List<String> chunks(Collection<Long> ids) {
        List<Long> all = ids.stream().filter(Objects::nonNull).distinct().toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 1000) {
            out.add(all.subList(i, Math.min(i + 1000, all.size())).stream()
                    .map(String::valueOf).collect(Collectors.joining(",")));
        }
        return out;
    }

    // ── Action log values ────────────────────────────────────────────────────

    private static final Pattern KEY = Pattern.compile("(?:^|[\\s,;|])([A-Za-z][A-Za-z0-9_]*)=");

    /**
     * Reads the action log's free-form values into facts: {@code "a=1; b=2"},
     * {@code "a=1 b=two words"} and {@code "a=1, b=2"} alike — a value runs up to the next
     * {@code key=}. Text before the first key is kept under {@code text}; "null" and empty
     * values are dropped.
     */
    public static Map<String, String> facts(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        Matcher m = KEY.matcher(raw);
        List<int[]> keys = new ArrayList<>();
        while (m.find()) keys.add(new int[]{m.start(1), m.end(1), m.end()});
        if (keys.isEmpty()) {
            out.put("text", raw.trim());
            return out;
        }
        String head = raw.substring(0, keys.get(0)[0]).replaceAll("[\\s,;|]+$", "").trim();
        if (!head.isEmpty()) out.put("text", head);
        for (int i = 0; i < keys.size(); i++) {
            int[] k = keys.get(i);
            String key = raw.substring(k[0], k[1]);
            int end = i + 1 < keys.size() ? keys.get(i + 1)[0] : raw.length();
            String value = raw.substring(k[2], end).replaceAll("[\\s,;|]+$", "").trim();
            if (!value.isEmpty() && !"null".equals(value)) out.put(key, value);
        }
        return out;
    }
}
