package com.daf360.rh.service;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.pipeline.PipelineSupport;
import com.daf360.rh.dto.pipeline.KanbanCandidateDto;
import com.daf360.rh.dto.pipeline.KanbanColumnDto;
import com.daf360.rh.dto.pipeline.PipelineActivityDto;
import com.daf360.rh.dto.pipeline.PipelineObjectiveDto;
import com.daf360.rh.dto.pipeline.PipelineStatsDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pipeline RH service — reads directly from [dbo].[candidates] via JdbcTemplate.
 * Avoids the Candidate JPA entity whose @ManyToOne FK columns
 * (applied_discipline_id, applied_grade_id, department_id, nationality_id)
 * don't exist in the current DB schema.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PipelineService {

    private final JdbcTemplate jdbc;

    // ── Stage constants ────────────────────────────────────────────────────────

    private static final List<String> STAGE_ORDER = List.of(
            "SCREENING", "ENTRETIEN", "OFFRE", "RECRUTE", "REJETE");

    // Status→stage, stage labels and the fit-score heuristic live in PipelineSupport
    // so the Kanban and the candidate list stay consistent.

    private static final Map<String, String> STAGE_TO_STATUS = Map.of(
            "SCREENING", "PENDING",
            "ENTRETIEN", "ACCEPTED",
            "OFFRE",     "OFFER_SENT",
            "RECRUTE",   "HIRED",
            "REJETE",    "REJECTED"
    );

    /**
     * Allowed candidate status transitions — mirrors the guarded workflow so the
     * generic {@link #moveToStage} endpoint can't jump to an arbitrary status
     * (e.g. PENDING → HIRED). Same-status is always a no-op.
     */
    private static final Map<String, java.util.Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING",        java.util.Set.of("ACCEPTED", "REJECTED"),
            "ACCEPTED",       java.util.Set.of("OFFER_SENT", "REJECTED"),
            "OFFER_SENT",     java.util.Set.of("IT_IN_PROGRESS", "REJECTED"),
            "IT_IN_PROGRESS", java.util.Set.of("EMAIL_RECEIVED", "REJECTED"),
            "EMAIL_RECEIVED", java.util.Set.of("HR_IN_PROGRESS", "HIRED", "REJECTED"),
            "HR_IN_PROGRESS", java.util.Set.of("HIRED", "REJECTED"),
            "HIRED",          java.util.Set.of("ARCHIVED"),
            "REJECTED",       java.util.Set.of("ARCHIVED")
    );

    // ── Shared Kanban query fragments (enriched card data) ───────────────────────

    /** Columns feeding {@link #rowToKanbanDto}. */
    private static final String KANBAN_COLUMNS = """
            c.id, c.first_name, c.last_name, c.applied_position, c.status,
            c.expected_start_date, c.notes, c.email_personal, c.created_at,
            c.experience_years, c.location, c.cv_path,
            ep.gender, ep.contract_type, ep.onboarding_completed,
            rd.budget_range, rd.technical_skills,
            CONVERT(datetime2, ni.scheduled_at) AS next_interview_at, ni.location AS next_interview_location,
            itp.name AS next_interview_type,
            ipv.status AS prov_status,
            jo.asked_salary AS offer_asked_salary, jo.proposed_salary AS offer_salary,
            jo.expiry_date AS offer_expiry, jo.status AS offer_status
            """;

    /** FROM + joins: employee profile, linked demand, IT provisioning and next planned interview. */
    private static final String KANBAN_FROM = """
             FROM [dbo].[candidates] c
             LEFT JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id AND ep.deleted = 0
             LEFT JOIN [dbo].[recruitment_demands] rd ON rd.id = c.recruitment_demand_id
             LEFT JOIN [dbo].[it_provisioning] ipv ON ipv.candidate_id = c.id
             OUTER APPLY (
                 SELECT TOP 1 ci.scheduled_at, ci.interview_type_id, ci.location
                 FROM [dbo].[candidate_interviews] ci
                 WHERE ci.candidate_id = c.id AND ci.status = 'PLANNED' AND ci.scheduled_at >= GETDATE()
                 ORDER BY ci.scheduled_at ASC
             ) ni
             LEFT JOIN [dbo].[interview_types] itp ON itp.id = ni.interview_type_id
             LEFT JOIN [dbo].[job_offers] jo ON jo.candidate_id = c.id
            """;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Statuses considered "active" (in-pipeline, not yet hired or rejected). */
    private static final String ACTIVE_IN = "'PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED'";

    /** Statuses shown in the Kanban (all except ARCHIVED). */
    private static final String KANBAN_IN = "'PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED','HIRED','REJECTED'";

    // ── Stats ──────────────────────────────────────────────────────────────────

    public PipelineStatsDto getStats() {
        String sql = """
            SELECT
                SUM(CASE WHEN status IN ('PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS',
                                         'IT_IN_PROGRESS','EMAIL_RECEIVED')
                         THEN 1 ELSE 0 END)                                      AS total,
                SUM(CASE WHEN status IN ('ACCEPTED','HR_IN_PROGRESS')
                         THEN 1 ELSE 0 END)                                      AS en_entretien,
                CAST(NULL AS FLOAT)                                              AS score_moyen,
                SUM(CASE WHEN status = 'HIRED' THEN 1 ELSE 0 END)               AS recrutements_clos,
                AVG(CASE WHEN status IN ('PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS',
                                         'IT_IN_PROGRESS','EMAIL_RECEIVED')
                         THEN CAST(DATEDIFF(day, created_at, GETDATE()) AS FLOAT)
                         END)                                                    AS avg_days,
                SUM(CASE WHEN expected_start_date IS NOT NULL
                              AND expected_start_date <= DATEADD(day, """ + PipelineSupport.URGENT_WINDOW_DAYS + """
                              , GETDATE())
                              AND status IN ('PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS',
                                            'IT_IN_PROGRESS','EMAIL_RECEIVED')
                         THEN 1 ELSE 0 END)                                      AS urgent
            FROM [dbo].[candidates]
            WHERE status <> 'ARCHIVED'
            """;

        Map<String, Object> row = jdbc.queryForMap(sql);
        long   total        = toLong(row.get("total"));
        long   enEntretien  = toLong(row.get("en_entretien"));
        double scoreMoyen   = toDouble(row.get("score_moyen"));
        long   recrutClos   = toLong(row.get("recrutements_clos"));
        double avg          = toDouble(row.get("avg_days"));
        long   urgent       = toLong(row.get("urgent"));

        return new PipelineStatsDto(
                total,
                enEntretien,
                Math.round(scoreMoyen * 10.0) / 10.0,
                recrutClos,
                Math.round(avg * 10.0) / 10.0,
                urgent);
    }

    // ── Recent activity ────────────────────────────────────────────────────────

    public List<PipelineActivityDto> getRecentActivity() {
        String sql = """
            SELECT TOP 20
                al.id,
                al.entity_id     AS candidate_id,
                al.action,
                CONVERT(datetime2, al.timestamp) AS timestamp,
                al.user_id       AS actor_id,
                c.first_name, c.last_name,
                u.fullName       AS actor_name
            FROM [dbo].[audit_log] al
            LEFT JOIN [dbo].[candidates] c ON c.id = TRY_CAST(al.entity_id AS BIGINT)
            LEFT JOIN [dbo].[Users]      u ON u.id = TRY_CAST(al.user_id   AS BIGINT)
            WHERE al.entity_type = 'CANDIDATE'
            ORDER BY al.timestamp DESC
            """;

        return jdbc.queryForList(sql).stream()
                .map(r -> {
                    String action = str(r, "action");
                    String firstName = str(r, "first_name");
                    String lastName  = str(r, "last_name");
                    String name = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
                    return new PipelineActivityDto(
                            toLong(r.get("id")),
                            toLong(r.get("candidate_id")),
                            name.isEmpty() ? null : name,
                            action,
                            deriveActionLabel(action),
                            deriveStageFromAction(action),
                            formatTimestamp(r.get("timestamp")),
                            str(r, "actor_name")
                    );
                })
                .toList();
    }

    private static String deriveActionLabel(String action) {
        if (action == null) return "Événement";
        return switch (action) {
            case "CREATE"                  -> "Candidature créée";
            case "UPDATE"                  -> "Profil mis à jour";
            case "ACCEPT"                  -> "Candidat accepté";
            case "REJECT"                  -> "Candidat rejeté";
            case "HIRE_CANDIDATE"          -> "Candidat recruté";
            case "UPLOAD_CV"               -> "CV téléversé";
            case "COMPLETE_IT_PROVISIONING"-> "Provisioning IT terminé";
            default                        -> action.replace('_', ' ');
        };
    }

    private static String deriveStageFromAction(String action) {
        if (action == null) return "SCREENING";
        return switch (action) {
            case "CREATE"                   -> "SCREENING";
            case "ACCEPT"                   -> "ENTRETIEN";
            case "HIRE_CANDIDATE"           -> "RECRUTE";
            case "REJECT"                   -> "REJETE";
            case "COMPLETE_IT_PROVISIONING" -> "OFFRE";
            default                         -> "SCREENING";
        };
    }

    private static String formatTimestamp(Object v) {
        if (v instanceof OffsetDateTime odt) {
            return odt.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.FRENCH));
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime()
                     .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.FRENCH));
        }
        return v != null ? v.toString() : null;
    }

    // ── Objectives ─────────────────────────────────────────────────────────────

    public List<PipelineObjectiveDto> getObjectives() {
        // Actuals: HIRED candidates grouped by month of updated_at (last 6 months)
        String actualsSql = """
            SELECT FORMAT(updated_at, 'yyyy-MM') AS ym, COUNT(*) AS cnt
            FROM [dbo].[candidates]
            WHERE status = 'HIRED'
              AND updated_at >= DATEADD(month, -6, GETDATE())
            GROUP BY FORMAT(updated_at, 'yyyy-MM')
            """;

        Map<String, Integer> actuals = new HashMap<>();
        jdbc.queryForList(actualsSql).forEach(r ->
                actuals.put(str(r, "ym"), (int) toLong(r.get("cnt"))));

        // Targets: sum headcount from recruitment_demands by target_start_date month
        String targetsSql = """
            SELECT FORMAT(target_start_date, 'yyyy-MM') AS ym, SUM(headcount) AS total
            FROM [dbo].[recruitment_demands]
            WHERE target_start_date >= DATEADD(month, -6, GETDATE())
              AND target_start_date <= DATEADD(month, 1, GETDATE())
            GROUP BY FORMAT(target_start_date, 'yyyy-MM')
            """;

        Map<String, Integer> targets = new HashMap<>();
        jdbc.queryForList(targetsSql).forEach(r ->
                targets.put(str(r, "ym"), (int) toLong(r.get("total"))));

        // Build last 6 months (oldest → newest)
        YearMonth now = YearMonth.now();
        List<PipelineObjectiveDto> result = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym  = now.minusMonths(i);
            String key    = ym.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            String label  = ym.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.FRENCH));
            result.add(new PipelineObjectiveDto(
                    key,
                    label,
                    targets.getOrDefault(key, 0),
                    actuals.getOrDefault(key, 0)
            ));
        }
        return result;
    }

    // ── Kanban ─────────────────────────────────────────────────────────────────

    public List<KanbanColumnDto> getKanban() {
        String sql = "SELECT " + KANBAN_COLUMNS + KANBAN_FROM +
                " WHERE c.status IN (" + KANBAN_IN + ") ORDER BY c.created_at DESC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        Map<String, List<KanbanCandidateDto>> byStage = rows.stream()
                .collect(Collectors.groupingBy(
                        r -> PipelineSupport.stageKey(str(r, "status")),
                        Collectors.mapping(this::rowToKanbanDto, Collectors.toList())
                ));

        return STAGE_ORDER.stream()
                .map(stage -> new KanbanColumnDto(
                        stage,
                        PipelineSupport.stageLabelForKey(stage),
                        byStage.getOrDefault(stage, List.of()).size(),
                        byStage.getOrDefault(stage, List.of())
                ))
                .toList();
    }

    // ── Paginated list ─────────────────────────────────────────────────────────

    public Page<KanbanCandidateDto> getCandidatesPaged(String stage, String search, Pageable pageable) {
        String inClause = resolveInClause(stage);
        boolean hasSearch = search != null && !search.isBlank();

        StringBuilder where = new StringBuilder("WHERE c.status IN (").append(inClause).append(")");
        List<Object> args = new ArrayList<>();

        if (hasSearch) {
            String like = "%" + search.trim() + "%";
            where.append(" AND (c.first_name LIKE ? OR c.last_name LIKE ? OR c.applied_position LIKE ?)");
            args.add(like); args.add(like); args.add(like);
        }

        // Count query (no JOIN needed)
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[candidates] c " + where,
                Long.class,
                args.toArray());

        // Data query — FETCH NEXT inlined (SQL Server JDBC rejects bound param)
        int offset   = (int) pageable.getOffset();
        int pageSize = pageable.getPageSize();
        String pagination = " ORDER BY c.created_at DESC" +
                " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + KANBAN_COLUMNS + KANBAN_FROM + " " + where + pagination,
                args.toArray());

        List<KanbanCandidateDto> content = rows.stream()
                .map(this::rowToKanbanDto)
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // ── Move to stage ──────────────────────────────────────────────────────────

    @Transactional
    public KanbanCandidateDto moveToStage(Long id, String stage) {
        String input = stage.trim().toUpperCase();

        // Accept raw CandidateStatus values (PENDING, ACCEPTED, IT_IN_PROGRESS…)
        // and fall back to kanban stage names (SCREENING, ENTRETIEN…) for compatibility.
        String newStatus;
        try {
            CandidateStatus.valueOf(input);
            newStatus = input;
        } catch (IllegalArgumentException e) {
            newStatus = STAGE_TO_STATUS.get(input);
            if (newStatus == null) {
                String validStatuses = java.util.Arrays.stream(CandidateStatus.values())
                        .map(Enum::name).collect(Collectors.joining(", "));
                throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                        "Statut inconnu : " + stage + ". Valeurs acceptées : " + validStatuses);
            }
        }

        // Validate the transition against the real workflow — no arbitrary jumps.
        String current;
        try {
            current = jdbc.queryForObject(
                    "SELECT status FROM [dbo].[candidates] WHERE id = ?", String.class, id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }
        if (!newStatus.equals(current)
                && !ALLOWED_TRANSITIONS.getOrDefault(current, java.util.Set.of()).contains(newStatus)) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Transition interdite : " + current + " → " + newStatus);
        }

        int updated = jdbc.update(
                "UPDATE [dbo].[candidates] SET status = ?, updated_at = SYSDATETIMEOFFSET() WHERE id = ?",
                newStatus, id);

        if (updated == 0) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT " + KANBAN_COLUMNS + KANBAN_FROM + " WHERE c.id = ?", id);

        return rowToKanbanDto(row);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private KanbanCandidateDto rowToKanbanDto(Map<String, Object> r) {
        String status   = str(r, "status");
        String stage    = PipelineSupport.stageKey(status);
        String stageLabel = PipelineSupport.stageLabelForKey(stage);
        LocalDate esd   = toLocalDate(r.get("expected_start_date"));
        boolean urgent  = esd != null && !esd.isAfter(LocalDate.now().plusDays(PipelineSupport.URGENT_WINDOW_DAYS));

        String firstName = str(r, "first_name");
        String lastName  = str(r, "last_name");
        String fullName  = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();

        String initials = deriveInitials(fullName);

        String applicationDate = formatDateFr(r.get("created_at"), "dd MMM yyyy");

        // ── Enriched card data ──────────────────────────────────────────────────
        Integer experienceYears = toIntOrNull(r.get("experience_years"));
        String experience = experienceYears != null ? experienceYears + " ans exp." : null;
        String location   = str(r, "location");
        boolean hasCv     = str(r, "cv_path") != null;
        int fitScore      = PipelineSupport.fitScore(status, hasCv, experienceYears);
        List<String> skills = parseSkills(str(r, "technical_skills"));
        String salary     = str(r, "budget_range");
        String nextEvent  = buildNextEvent(r.get("next_interview_at"), str(r, "next_interview_type"));
        Integer progressPercent = PipelineSupport.progressPercent(
                status, str(r, "prov_status"), toBool(r.get("onboarding_completed")));

        // ── Entretien / Offre per-column card data ──────────────────────────────
        String interviewLocation = str(r, "next_interview_location");
        String askedSalary = formatSalary(r.get("offer_asked_salary"));
        String proposedSalary = formatSalary(r.get("offer_salary"));
        String offerExpiry = formatDateFr(r.get("offer_expiry"), "dd/MM/yyyy");
        String offerStatus = str(r, "offer_status");

        return new KanbanCandidateDto(
                toLong(r.get("id")),
                fullName,
                initials,
                null,
                str(r, "applied_position"),
                fitScore,
                deriveBadge(status, urgent),
                deriveBadgeType(status, urgent),
                experience,
                location,
                skills,
                str(r, "notes"),
                nextEvent,
                salary,
                urgent,
                stage,
                stageLabel,
                applicationDate,
                str(r, "email_personal"),
                status,
                str(r, "gender"),
                str(r, "contract_type"),
                progressPercent,
                interviewLocation,
                askedSalary,
                proposedSalary,
                offerExpiry,
                offerStatus
        );
    }

    /** Parses a JSON array string (e.g. {@code ["Java","AWS"]}) into a list; empty on any failure. */
    private static List<String> parseSkills(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Builds a compact "Type · 12 juil. 14:00" label for the next planned interview. */
    private static String buildNextEvent(Object scheduledAt, String typeName) {
        String when = formatDateFr(scheduledAt, "dd MMM HH:mm");
        if (when == null) return null;
        return typeName != null && !typeName.isBlank() ? typeName + " · " + when : when;
    }

    private static String formatDateFr(Object v, String pattern) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern, Locale.FRENCH);
        if (v instanceof OffsetDateTime odt) return odt.format(fmt);
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(fmt);
        if (v instanceof java.sql.Date d) return d.toLocalDate().format(fmt);
        if (v instanceof LocalDate ld) return ld.format(fmt);
        return null;
    }

    /** Formats a numeric salary as a compact French label, e.g. {@code 65000 → "65 000 DT"}. */
    private static String formatSalary(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) {
            return String.format(Locale.FRENCH, "%,.0f DT", n.doubleValue());
        }
        return v.toString();
    }

    private static Integer toIntOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean toBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = v.toString().trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String deriveInitials(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length >= 2) {
            return (String.valueOf(parts[0].charAt(0)) + String.valueOf(parts[1].charAt(0))).toUpperCase();
        }
        return String.valueOf(parts[0].charAt(0)).toUpperCase();
    }

    private String resolveInClause(String stage) {
        if (stage == null || stage.isBlank()) return ACTIVE_IN;
        return switch (stage.toUpperCase()) {
            case "SCREENING" -> "'PENDING'";
            case "ENTRETIEN" -> "'ACCEPTED','HR_IN_PROGRESS'";
            case "OFFRE"     -> "'OFFER_SENT','IT_IN_PROGRESS','EMAIL_RECEIVED'";
            case "RECRUTE"   -> "'HIRED'";
            case "REJETE"    -> "'REJECTED','ARCHIVED'";
            default          -> ACTIVE_IN;
        };
    }

    private static String deriveBadge(String status, boolean urgent) {
        return switch (status) {
            case "PENDING"                       -> urgent ? "Urgent" : "Nouveau";
            case "ACCEPTED", "HR_IN_PROGRESS"    -> "En cours";
            case "OFFER_SENT",
                 "IT_IN_PROGRESS",
                 "EMAIL_RECEIVED"                -> "Offre Envoyée";
            case "HIRED"                         -> "Confirmé";
            default                              -> "Rejeté";
        };
    }

    private static String deriveBadgeType(String status, boolean urgent) {
        return switch (status) {
            case "PENDING"                       -> urgent ? "urgent" : "new";
            case "ACCEPTED", "HR_IN_PROGRESS"    -> "in_progress";
            case "OFFER_SENT",
                 "IT_IN_PROGRESS",
                 "EMAIL_RECEIVED"                -> "offer";
            case "HIRED"                         -> "hired";
            default                              -> "rejected";
        };
    }

    // ── Type-safe column extractors ────────────────────────────────────────────

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return 0L; }
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        return ((Number) v).doubleValue();
    }

    private static String str(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? null : v.toString();
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof OffsetDateTime odt) return odt.toLocalDate();
        return null;
    }
}
