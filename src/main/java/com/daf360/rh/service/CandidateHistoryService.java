package com.daf360.rh.service;

import com.daf360.rh.dto.candidate.CandidateHistoryItem;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The recruitment history: every action taken on candidatures, newest first, with who took it.
 *
 * Two readers:
 *  - {@link #history(Long)} — ONE candidate, whole history (the candidate page's step rail
 *    and the decision drawer of /rh/candidates/list);
 *  - {@link #globalHistory(LocalDate, LocalDate)} — every candidate of the caller's entities,
 *    over a period (the « Historique » tab of /rh/candidates).
 *
 * Two kinds of source, merged into one list:
 *  - the action log ({@code audit_log}), written at the call site by the candidate, offer,
 *    IT-provisioning and onboarding services — creation, edits, CV, acceptance / rejection,
 *    every offer round, the hire;
 *  - the candidature's own tables, for the steps whose services never wrote to that log:
 *    interviews (planned, cancelled, validated by RH), interviewers' PVs, and the finance
 *    decisions on the salary. Their timestamp and actor columns ARE the record, so the
 *    history goes back to the first candidature, not only to the day this screen shipped.
 *
 * Read-only, no new table. Salary figures are reserved to full-record readers, the same rule
 * as the candidate record.
 *
 * Note on {@code audit_log}: its columns are camelCase ({@code entityType}, {@code userId},
 * {@code newValue}) — the previous version of the per-candidate query used snake_case names,
 * failed on every call, and its readers (which swallow the error) always showed nothing.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CandidateHistoryService {

    /** Global history without a chosen period: this far back. */
    static final int DEFAULT_WINDOW_DAYS = 365;
    /** Global history: never more lines than this (newest kept). */
    static final int GLOBAL_MAX_ROWS = 3000;

    private final JdbcTemplate        jdbc;
    private final CandidateRepository candidateRepo;
    private final InterviewAccess     interviewAccess;
    private final TenantService       tenantService;

    /** Action log codes → the category of the line. Anything unlisted is CANDIDATE. */
    private static final Set<String> OFFER_ACTIONS = Set.of(
            "DRAFT_OFFER", "SEND_OFFER", "FINANCE_COUNTER_OFFER", "OFFER_AGREED", "ACCEPT_OFFER", "REJECT_OFFER");

    /** Detail keys that carry a salary — hidden from readers without full-record access. */
    private static final Set<String> SALARY_KEYS = Set.of("proposedSalary", "counterSalary");

    /** French fallbacks — the screen translates {@code action} first. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("CREATE",                      "Candidature créée"),
            Map.entry("CREATE_CANDIDATE",            "Candidature créée"),
            Map.entry("UPDATE",                      "Profil modifié"),
            Map.entry("UPDATE_CANDIDATE",            "Profil modifié"),
            Map.entry("UPLOAD_CV",                   "CV téléversé"),
            Map.entry("ACCEPT",                      "Candidature validée"),
            Map.entry("ACCEPT_CANDIDATE",            "Candidature validée"),
            Map.entry("REJECT",                      "Candidature refusée"),
            Map.entry("REJECT_CANDIDATE",            "Candidature refusée"),
            Map.entry("DRAFT_OFFER",                 "Offre préparée"),
            Map.entry("SEND_OFFER",                  "Offre envoyée"),
            Map.entry("FINANCE_COUNTER_OFFER",       "Contre-proposition DAF"),
            Map.entry("OFFER_AGREED",                "Accord sur la contre-proposition du candidat"),
            Map.entry("ACCEPT_OFFER",                "Offre acceptée"),
            Map.entry("REJECT_OFFER",                "Offre refusée"),
            Map.entry("HIRE_CANDIDATE",              "Embauche"),
            Map.entry("UPDATE_IT_PROVISIONING",      "Provisioning IT mis à jour"),
            Map.entry("SUBMIT_MS365_EMAIL",          "Compte Microsoft 365 créé"),
            Map.entry("COMPLETE_IT_PROVISIONING",    "Provisioning IT terminé"),
            Map.entry("COMPLETE_ONBOARDING_PROFILE", "Dossier RH complété"),
            Map.entry("INTERVIEW_PLANNED",           "Entretien planifié"),
            Map.entry("INTERVIEW_CANCELLED",         "Entretien annulé"),
            Map.entry("INTERVIEW_PV",                "PV d'entretien déposé"),
            Map.entry("INTERVIEW_VALIDATED",         "Entretien validé par RH"),
            Map.entry("FINANCE_SUBMITTED",           "Envoyé en validation financière"),
            Map.entry("FINANCE_DECIDED",             "Décision financière"));

    /**
     * Which candidates a read covers, as a sub-select returning one {@code id} column, and the
     * period it covers (UTC bounds, null = unbounded). The candidate ids are inlined (Longs or
     * the scope's own sub-select — never user text), so every source query has the same shape.
     */
    private record Range(String idsSql, LocalDateTime from, LocalDateTime to) {
        boolean contains(LocalDateTime at) {
            return at != null && (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
        }
    }

    // ── Readers ──────────────────────────────────────────────────────────────

    public List<CandidateHistoryItem> history(Long candidateId) {
        if (!candidateRepo.existsById(candidateId)) throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND);
        return build(new Range("SELECT CAST(" + candidateId + " AS BIGINT) AS id", null, null),
                false, Integer.MAX_VALUE);
    }

    /**
     * Every candidate of the caller's entities over [from, to] (calendar days, both included).
     * The bounds are widened by a day on each side — the dates are the user's local days and
     * the screen trims to them exactly; the server only has to bring enough back. No period =
     * the last {@value #DEFAULT_WINDOW_DAYS} days; at most {@value #GLOBAL_MAX_ROWS} lines.
     */
    public List<CandidateHistoryItem> globalHistory(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(ZoneOffset.UTC).minusDays(DEFAULT_WINDOW_DAYS);
        LocalDate end   = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        if (end.isBefore(start)) { LocalDate t = start; start = end; end = t; }
        Range range = new Range(scopedCandidateIds(),
                start.minusDays(1).atStartOfDay(), end.plusDays(2).atStartOfDay());
        return build(range, true, GLOBAL_MAX_ROWS);
    }

    /** The candidates the caller may see — same entity scope as the board (PipelineService). */
    private String scopedCandidateIds() {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        String base = "SELECT c.id FROM [dbo].[candidates] c";
        if (scope.unfiltered()) return base;
        if (scope.paysIds().isEmpty()) return base + " WHERE 1 = 0";
        return base + " WHERE c.pays_id IN (" + scope.paysIds().stream()
                .map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }

    private List<CandidateHistoryItem> build(Range range, boolean withCandidate, int max) {
        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(fromActionLog(range));
        drafts.addAll(fromInterviews(range));
        drafts.addAll(fromReports(range));
        drafts.addAll(fromFinance(range));

        List<Draft> kept = drafts.stream()
                .filter(d -> range.contains(d.at))
                .sorted(Comparator.comparing((Draft d) -> d.at).reversed()
                        .thenComparing((Draft d) -> d.id, Comparator.<String>reverseOrder()))
                .limit(max)
                .toList();

        // Same rule as the candidate record (getCandidateForViewer): salaries are for the
        // full-record readers only. A reader without it still sees every action, not the figures.
        if (!interviewAccess.hasFullRecordAccess()) {
            kept.forEach(d -> d.details.keySet().removeAll(SALARY_KEYS));
        }

        Map<Long, String> names = actorNames(kept.stream()
                .map(d -> d.actorId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, CandidateRef> candidates = withCandidate
                ? candidateRefs(kept.stream().map(d -> d.candidateId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                : Map.of();

        return kept.stream().map(d -> {
            CandidateRef c = d.candidateId != null ? candidates.get(d.candidateId) : null;
            return CandidateHistoryItem.builder()
                    .id(d.id)
                    .timestamp(d.at.toInstant(ZoneOffset.UTC).toString())
                    .category(d.category)
                    .action(d.action)
                    .actionLabel(LABELS.getOrDefault(d.action, d.action))
                    .performedByUserId(d.actorId)
                    .performedByName(d.actorId != null ? names.get(d.actorId) : null)
                    .comment(blankToNull(d.comment))
                    .resultingStatus(d.candidateStatus)
                    .status(d.status)
                    .details(d.details.isEmpty() ? null : d.details)
                    .candidateId(withCandidate ? d.candidateId : null)
                    .candidateName(c != null ? c.name() : null)
                    .candidatePosition(c != null ? c.position() : null)
                    .candidateGender(c != null ? c.gender() : null)
                    .build();
        }).toList();
    }

    // ── Action log ───────────────────────────────────────────────────────────

    /**
     * The candidates' own lines, plus their IT files' and the RH file that created their
     * employee profile — both logged against their own entity, linked back through
     * candidate_id. The period is applied in SQL here (the log is the big table).
     * Timestamps are normalised to UTC DATETIME2 (the driver's DateTimeOffset type does not
     * map to a JDBC Timestamp cleanly — same reason as AuditQueryRepository).
     */
    private List<Draft> fromActionLog(Range range) {
        String cols = """
                al.id, CAST(al.userId AS NVARCHAR(200)) AS actor, al.action, al.entityType,
                CAST(al.newValue AS NVARCHAR(MAX)) AS new_value,
                CAST(SWITCHOFFSET(al.[timestamp], '+00:00') AS DATETIME2) AS ts""";
        StringBuilder period = new StringBuilder();
        List<Object> one = new ArrayList<>();
        if (range.from() != null) { period.append(" AND al.[timestamp] >= ?"); one.add(range.from()); }
        if (range.to()   != null) { period.append(" AND al.[timestamp] < ?");  one.add(range.to()); }

        String sql = "SELECT " + cols + ", s.id AS cand_id"
                + " FROM [dbo].[audit_log] al"
                + " JOIN (" + range.idsSql() + ") s ON al.entityId = CAST(s.id AS NVARCHAR(100))"
                + " WHERE al.entityType = 'CANDIDATE'" + period
                + " UNION ALL"
                + " SELECT " + cols + ", ip.candidate_id AS cand_id"
                + " FROM [dbo].[audit_log] al"
                + " JOIN [dbo].[it_provisioning] ip ON al.entityId = CAST(ip.id AS NVARCHAR(100))"
                + " WHERE al.entityType = 'IT_PROVISIONING'"
                + " AND ip.candidate_id IN (SELECT s.id FROM (" + range.idsSql() + ") s)" + period
                + " UNION ALL"
                + " SELECT " + cols + ", ep.candidate_id AS cand_id"
                + " FROM [dbo].[audit_log] al"
                + " JOIN [dbo].[employee_profiles] ep ON al.entityId = CAST(ep.id AS NVARCHAR(100))"
                + " WHERE al.entityType = 'EMPLOYEE_PROFILE' AND al.action = 'COMPLETE_ONBOARDING_PROFILE'"
                + " AND ep.candidate_id IN (SELECT s.id FROM (" + range.idsSql() + ") s)" + period;

        List<Object> params = new ArrayList<>();
        for (int i = 0; i < 3; i++) params.addAll(one);

        return jdbc.query(sql, (rs, n) -> {
            String action = rs.getString("action");
            String entity = rs.getString("entityType");
            Map<String, String> facts = parseFacts(rs.getString("new_value"));
            String rawStatus = statusToken(facts.remove("status"));

            Draft d = new Draft("log-" + rs.getLong("id"), ts(rs, "ts"), parseLong(rs.getString("actor")),
                    action, nullableLong(rs, "cand_id"));
            d.comment = facts.remove("reason");
            if ("IT_PROVISIONING".equals(entity)) {
                d.category = "ONBOARDING";
                d.status = rawStatus != null ? "IT_" + rawStatus : null;
                facts.remove("userId");
            } else if ("EMPLOYEE_PROFILE".equals(entity)) {
                d.category = "ONBOARDING";
                d.status = "PROFILE_CREATED";
                facts.clear();
            } else {
                d.category = OFFER_ACTIONS.contains(action) ? "OFFER" : "CANDIDATE";
                // An offer round being prepared (by RH or the DAF) changes no candidate status:
                // its "status=DRAFT" is the round's, not the candidate's.
                boolean draftRound = "DRAFT_OFFER".equals(action) || "FINANCE_COUNTER_OFFER".equals(action);
                d.candidateStatus = draftRound ? null : rawStatus;
                d.status = draftRound ? "OFFER_DRAFT" : rawStatus;
                // A profile edit logs ids (demand, employment type) that mean nothing on screen.
                if (action != null && action.startsWith("UPDATE")) facts.clear();
            }
            d.details.putAll(facts);
            return d;
        }, params.toArray());
    }

    // ── Interviews ───────────────────────────────────────────────────────────

    /** Planned (creation), cancelled (last update of a cancelled one), validated by RH. */
    private List<Draft> fromInterviews(Range range) {
        String sql = """
                SELECT ci.id, ci.candidate_id, ci.status, ci.result, ci.sequence_number, ci.created_by,
                       ci.validated_by, ci.validation_comment,
                       CAST(SWITCHOFFSET(ci.created_at,   '+00:00') AS DATETIME2) AS created_at,
                       CAST(SWITCHOFFSET(ci.updated_at,   '+00:00') AS DATETIME2) AS updated_at,
                       CAST(SWITCHOFFSET(ci.validated_at, '+00:00') AS DATETIME2) AS validated_at,
                       CAST(SWITCHOFFSET(ci.scheduled_at, '+00:00') AS DATETIME2) AS scheduled_at,
                       it.name AS type_name, it.name_en AS type_name_en
                FROM [dbo].[candidate_interviews] ci
                LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id
                WHERE ci.candidate_id IN (SELECT s.id FROM (%s) s)
                """.formatted(range.idsSql());
        List<Draft> out = new ArrayList<>();
        jdbc.query(sql, rs -> {
            long id = rs.getLong("id");
            Long cand = nullableLong(rs, "candidate_id");
            Map<String, String> base = new LinkedHashMap<>();
            putIfPresent(base, "interviewType",   rs.getString("type_name"));
            putIfPresent(base, "interviewTypeEn", rs.getString("type_name_en"));
            putIfPresent(base, "sequence",        rs.getString("sequence_number"));

            Draft planned = new Draft("itv-" + id + "-planned", ts(rs, "created_at"),
                    nullableLong(rs, "created_by"), "INTERVIEW_PLANNED", cand);
            planned.category = "INTERVIEW";
            planned.status = "PLANNED";
            planned.details.putAll(base);
            Timestamp scheduled = rs.getTimestamp("scheduled_at");
            if (scheduled != null) {
                planned.details.put("scheduledAt",
                        scheduled.toLocalDateTime().toInstant(ZoneOffset.UTC).toString());
            }
            out.add(planned);

            if ("CANCELLED".equals(rs.getString("status"))) {
                // Who cancelled is not stored on the row — the line says when, not who.
                Draft cancelled = new Draft("itv-" + id + "-cancelled", ts(rs, "updated_at"), null,
                        "INTERVIEW_CANCELLED", cand);
                cancelled.category = "INTERVIEW";
                cancelled.status = "CANCELLED";
                cancelled.details.putAll(base);
                out.add(cancelled);
            }

            if (rs.getTimestamp("validated_at") != null) {
                Draft validated = new Draft("itv-" + id + "-validated", ts(rs, "validated_at"),
                        nullableLong(rs, "validated_by"), "INTERVIEW_VALIDATED", cand);
                validated.category = "INTERVIEW";
                validated.status = rs.getString("result"); // PASS | FAIL
                validated.comment = rs.getString("validation_comment");
                validated.details.putAll(base);
                out.add(validated);
            }
        });
        return out;
    }

    /** One line per interviewer's PV — its author and their opinion. */
    private List<Draft> fromReports(Range range) {
        String sql = """
                SELECT r.id, ci.candidate_id, r.author_user_id, r.opinion,
                       CAST(SWITCHOFFSET(r.submitted_at, '+00:00') AS DATETIME2) AS submitted_at,
                       it.name AS type_name, it.name_en AS type_name_en
                FROM [dbo].[candidate_interview_reports] r
                JOIN [dbo].[candidate_interviews] ci ON ci.id = r.interview_id
                LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id
                WHERE ci.candidate_id IN (SELECT s.id FROM (%s) s)
                """.formatted(range.idsSql());
        return jdbc.query(sql, (rs, n) -> {
            Draft d = new Draft("pv-" + rs.getLong("id"), ts(rs, "submitted_at"),
                    nullableLong(rs, "author_user_id"), "INTERVIEW_PV", nullableLong(rs, "candidate_id"));
            d.category = "INTERVIEW";
            d.status = "PV_FILED";
            putIfPresent(d.details, "interviewType",   rs.getString("type_name"));
            putIfPresent(d.details, "interviewTypeEn", rs.getString("type_name_en"));
            putIfPresent(d.details, "opinion",         rs.getString("opinion"));
            return d;
        });
    }

    // ── Finance ──────────────────────────────────────────────────────────────

    /** Each salary request sent to finance, and its decision once taken. */
    private List<Draft> fromFinance(Range range) {
        String sql = """
                SELECT a.id, a.candidate_id, a.status, a.submitted_by, a.approved_by, a.approval_notes,
                       a.proposed_salary, a.salaire_net_rh, a.contre_prop_salaire,
                       CAST(SWITCHOFFSET(a.submitted_at, '+00:00') AS DATETIME2) AS submitted_at,
                       CAST(SWITCHOFFSET(a.approved_at,  '+00:00') AS DATETIME2) AS approved_at,
                       jo.round_number
                FROM [dbo].[candidate_cost_approvals] a
                LEFT JOIN [dbo].[job_offers] jo ON jo.id = a.job_offer_id
                WHERE a.candidate_id IN (SELECT s.id FROM (%s) s)
                """.formatted(range.idsSql());
        List<Draft> out = new ArrayList<>();
        jdbc.query(sql, rs -> {
            long id = rs.getLong("id");
            Long cand = nullableLong(rs, "candidate_id");
            java.math.BigDecimal proposed = rs.getBigDecimal("proposed_salary");
            if (proposed == null) proposed = rs.getBigDecimal("salaire_net_rh");
            String salary = proposed != null ? proposed.stripTrailingZeros().toPlainString() : null;
            String round = rs.getString("round_number");

            Draft submitted = new Draft("fin-" + id + "-submitted", ts(rs, "submitted_at"),
                    nullableLong(rs, "submitted_by"), "FINANCE_SUBMITTED", cand);
            submitted.category = "FINANCE";
            submitted.status = "FINANCE_PENDING";
            putIfPresent(submitted.details, "round", round);
            putIfPresent(submitted.details, "proposedSalary", salary);
            out.add(submitted);

            String status = rs.getString("status");
            if (rs.getTimestamp("approved_at") != null && !"PENDING".equals(status)) {
                Draft decided = new Draft("fin-" + id + "-decided", ts(rs, "approved_at"),
                        nullableLong(rs, "approved_by"), "FINANCE_DECIDED", cand);
                decided.category = "FINANCE";
                decided.status = "FINANCE_" + status; // FINANCE_APPROVED | FINANCE_REJECTED
                decided.comment = rs.getString("approval_notes");
                putIfPresent(decided.details, "round", round);
                putIfPresent(decided.details, "proposedSalary", salary);
                java.math.BigDecimal counter = rs.getBigDecimal("contre_prop_salaire");
                if (counter != null) decided.details.put("counterSalary", counter.stripTrailingZeros().toPlainString());
                out.add(decided);
            }
        });
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record CandidateRef(String name, String position, String gender) {}

    /** Name, position and gender (for the avatar) of the candidates the lines are about. */
    private Map<Long, CandidateRef> candidateRefs(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<Long, CandidateRef> out = new HashMap<>();
        List<Long> all = new ArrayList<>(ids);
        // Chunked: SQL Server caps a statement at 2100 parameters — inlined Longs are safe anyway.
        for (int i = 0; i < all.size(); i += 1000) {
            String in = all.subList(i, Math.min(i + 1000, all.size())).stream()
                    .map(String::valueOf).collect(Collectors.joining(","));
            jdbc.query("""
                    SELECT c.id, c.first_name, c.last_name, c.applied_position, ep.gender
                    FROM [dbo].[candidates] c
                    LEFT JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id AND ep.deleted = 0
                    WHERE c.id IN (%s)
                    """.formatted(in), rs -> {
                String name = ((rs.getString("first_name") == null ? "" : rs.getString("first_name")) + " "
                        + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim();
                out.putIfAbsent(rs.getLong("id"),
                        new CandidateRef(name, rs.getString("applied_position"), rs.getString("gender")));
            });
        }
        return out;
    }

    private Map<Long, String> actorNames(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, String> out = new HashMap<>();
        jdbc.query("SELECT id, fullName FROM [dbo].[Users] WHERE id IN (" + in + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("fullName")); });
        return out;
    }

    /** {@code "status=REJECTED; reason=Trop cher"} → {status: REJECTED, reason: Trop cher}. */
    static Map<String, String> parseFacts(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            if (!k.isEmpty() && !v.isEmpty() && !"null".equals(v)) out.put(k, v);
        }
        return out;
    }

    /** {@code "HR_IN_PROGRESS (onboarding + IT)"} → {@code HR_IN_PROGRESS}. */
    static String statusToken(String v) {
        if (v == null) return null;
        String t = v.trim().split("\\s+")[0];
        return t.isEmpty() ? null : t;
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t != null ? t.toLocalDateTime() : null;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static void putIfPresent(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A line before actor and candidate names are resolved. {@code at} is UTC. */
    private static final class Draft {
        final String id;
        final LocalDateTime at;
        final Long actorId;
        final String action;
        final Long candidateId;
        String category = "CANDIDATE";
        String status;
        String candidateStatus;
        String comment;
        final Map<String, String> details = new LinkedHashMap<>();

        Draft(String id, LocalDateTime at, Long actorId, String action, Long candidateId) {
            this.id = id;
            this.at = at;
            this.actorId = actorId;
            this.action = action;
            this.candidateId = candidateId;
        }
    }
}
