package com.daf360.rh.service;

import com.daf360.rh.domain.enums.CandidateStatus;
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

    private static final Map<String, String> STATUS_TO_STAGE = Map.of(
            "PENDING",        "SCREENING",
            "ACCEPTED",       "ENTRETIEN",
            "HR_IN_PROGRESS", "ENTRETIEN",
            "IT_IN_PROGRESS", "OFFRE",
            "EMAIL_RECEIVED", "OFFRE",
            "HIRED",          "RECRUTE",
            "REJECTED",       "REJETE",
            "ARCHIVED",       "REJETE"
    );

    private static final Map<String, String> STAGE_TO_STATUS = Map.of(
            "SCREENING", "PENDING",
            "ENTRETIEN", "ACCEPTED",
            "OFFRE",     "HR_IN_PROGRESS",
            "RECRUTE",   "HIRED",
            "REJETE",    "REJECTED"
    );

    private static final Map<String, String> STAGE_LABELS = Map.of(
            "SCREENING", "Candidatures",
            "ENTRETIEN", "Entretiens",
            "OFFRE",     "Offres",
            "RECRUTE",   "Recrutés",
            "REJETE",    "Rejetés"
    );

    /** Statuses considered "active" (in-pipeline, not yet hired or rejected). */
    private static final String ACTIVE_IN = "'PENDING','ACCEPTED','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED'";

    /** Statuses shown in the Kanban (all except ARCHIVED). */
    private static final String KANBAN_IN = "'PENDING','ACCEPTED','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED','HIRED','REJECTED'";

    // ── Stats ──────────────────────────────────────────────────────────────────

    public PipelineStatsDto getStats() {
        String sql = """
            SELECT
                SUM(CASE WHEN status IN ('PENDING','ACCEPTED','HR_IN_PROGRESS',
                                         'IT_IN_PROGRESS','EMAIL_RECEIVED')
                         THEN 1 ELSE 0 END)                                      AS total,
                SUM(CASE WHEN status IN ('ACCEPTED','HR_IN_PROGRESS')
                         THEN 1 ELSE 0 END)                                      AS en_entretien,
                CAST(NULL AS FLOAT)                                              AS score_moyen,
                SUM(CASE WHEN status = 'HIRED' THEN 1 ELSE 0 END)               AS recrutements_clos,
                AVG(CASE WHEN status IN ('PENDING','ACCEPTED','HR_IN_PROGRESS',
                                         'IT_IN_PROGRESS','EMAIL_RECEIVED')
                         THEN CAST(DATEDIFF(day, created_at, GETDATE()) AS FLOAT)
                         END)                                                    AS avg_days,
                SUM(CASE WHEN expected_start_date IS NOT NULL
                              AND expected_start_date <= DATEADD(day, 30, GETDATE())
                              AND status IN ('PENDING','ACCEPTED','HR_IN_PROGRESS',
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
        String sql = """
            SELECT c.id, c.first_name, c.last_name, c.applied_position, c.status,
                   c.expected_start_date, c.notes, c.email_personal, c.created_at,
                   ep.gender, ep.contract_type
            FROM [dbo].[candidates] c
            LEFT JOIN [dbo].[employee_profiles] ep
                   ON ep.candidate_id = c.id AND ep.deleted = 0
            WHERE c.status IN (""" + KANBAN_IN + ")\n" +
            "ORDER BY c.created_at DESC";

        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        Map<String, List<KanbanCandidateDto>> byStage = rows.stream()
                .collect(Collectors.groupingBy(
                        r -> STATUS_TO_STAGE.getOrDefault(str(r, "status"), "SCREENING"),
                        Collectors.mapping(this::rowToKanbanDto, Collectors.toList())
                ));

        return STAGE_ORDER.stream()
                .map(stage -> new KanbanColumnDto(
                        stage,
                        STAGE_LABELS.getOrDefault(stage, stage),
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
                "SELECT c.id, c.first_name, c.last_name, c.applied_position, c.status," +
                " c.expected_start_date, c.notes, c.email_personal, c.created_at," +
                " ep.gender, ep.contract_type" +
                " FROM [dbo].[candidates] c" +
                " LEFT JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id AND ep.deleted = 0 " +
                where + pagination,
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

        int updated = jdbc.update(
                "UPDATE [dbo].[candidates] SET status = ?, updated_at = SYSDATETIMEOFFSET() WHERE id = ?",
                newStatus, id);

        if (updated == 0) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT c.id, c.first_name, c.last_name, c.applied_position, c.status," +
                " c.expected_start_date, c.notes, c.email_personal, c.created_at," +
                " ep.gender, ep.contract_type" +
                " FROM [dbo].[candidates] c" +
                " LEFT JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id AND ep.deleted = 0" +
                " WHERE c.id = ?", id);

        return rowToKanbanDto(row);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private KanbanCandidateDto rowToKanbanDto(Map<String, Object> r) {
        String status   = str(r, "status");
        String stage    = STATUS_TO_STAGE.getOrDefault(status, "SCREENING");
        String stageLabel = STAGE_LABELS.getOrDefault(stage, stage);
        LocalDate esd   = toLocalDate(r.get("expected_start_date"));
        boolean urgent  = esd != null && !esd.isAfter(LocalDate.now().plusDays(14));

        String firstName = str(r, "first_name");
        String lastName  = str(r, "last_name");
        String fullName  = ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();

        String initials = deriveInitials(fullName);

        String applicationDate = null;
        Object createdAt = r.get("created_at");
        if (createdAt instanceof OffsetDateTime odt) {
            applicationDate = odt.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.FRENCH));
        } else if (createdAt instanceof java.sql.Timestamp ts) {
            applicationDate = ts.toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.FRENCH));
        }

        return new KanbanCandidateDto(
                toLong(r.get("id")),
                fullName,
                initials,
                null,
                str(r, "applied_position"),
                0,
                deriveBadge(status, urgent),
                deriveBadgeType(status, urgent),
                null,
                null,
                List.of(),
                str(r, "notes"),
                null,
                null,
                urgent,
                stage,
                stageLabel,
                applicationDate,
                str(r, "email_personal"),
                status,
                str(r, "gender"),
                str(r, "contract_type")
        );
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
            case "OFFRE"     -> "'IT_IN_PROGRESS','EMAIL_RECEIVED'";
            case "RECRUTE"   -> "'HIRED'";
            case "REJETE"    -> "'REJECTED','ARCHIVED'";
            default          -> ACTIVE_IN;
        };
    }

    private static String deriveBadge(String status, boolean urgent) {
        return switch (status) {
            case "PENDING"                       -> urgent ? "Urgent" : "Nouveau";
            case "ACCEPTED", "HR_IN_PROGRESS"    -> "En cours";
            case "IT_IN_PROGRESS",
                 "EMAIL_RECEIVED"                -> "Offre Envoyée";
            case "HIRED"                         -> "Confirmé";
            default                              -> "Rejeté";
        };
    }

    private static String deriveBadgeType(String status, boolean urgent) {
        return switch (status) {
            case "PENDING"                       -> urgent ? "urgent" : "new";
            case "ACCEPTED", "HR_IN_PROGRESS"    -> "in_progress";
            case "IT_IN_PROGRESS",
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
