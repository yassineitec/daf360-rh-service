package com.daf360.rh.service;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.dto.pipeline.KanbanCandidateDto;
import com.daf360.rh.dto.pipeline.KanbanColumnDto;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    // ── Kanban ─────────────────────────────────────────────────────────────────

    public List<KanbanColumnDto> getKanban() {
        String sql = """
            SELECT id, first_name, last_name, applied_position, status,
                   expected_start_date, notes, email_personal, created_at
            FROM [dbo].[candidates]
            WHERE status IN (""" + KANBAN_IN + ")\n" +
            "ORDER BY created_at DESC";

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

        StringBuilder where = new StringBuilder("WHERE status IN (").append(inClause).append(")");
        List<Object> args = new ArrayList<>();

        if (hasSearch) {
            String like = "%" + search.trim() + "%";
            where.append(" AND (first_name LIKE ? OR last_name LIKE ? OR applied_position LIKE ?)");
            args.add(like); args.add(like); args.add(like);
        }

        // Count query
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[candidates] " + where,
                Long.class,
                args.toArray());

        // Data query — FETCH NEXT inlined (SQL Server JDBC rejects bound param)
        int offset   = (int) pageable.getOffset();
        int pageSize = pageable.getPageSize();
        String dataArgs = where +
                " ORDER BY created_at DESC" +
                " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, first_name, last_name, applied_position, status, expected_start_date, notes," +
                " email_personal, created_at FROM [dbo].[candidates] " + dataArgs,
                args.toArray());

        List<KanbanCandidateDto> content = rows.stream()
                .map(this::rowToKanbanDto)
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // ── Move to stage ──────────────────────────────────────────────────────────

    @Transactional
    public KanbanCandidateDto moveToStage(Long id, String stage) {
        String newStatus = STAGE_TO_STATUS.get(stage.toUpperCase());
        if (newStatus == null) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Stage inconnu : " + stage + ". Valeurs acceptées : "
                    + String.join(", ", STAGE_TO_STATUS.keySet()));
        }

        int updated = jdbc.update(
                "UPDATE [dbo].[candidates] SET status = ?, updated_at = SYSDATETIMEOFFSET() WHERE id = ?",
                newStatus, id);

        if (updated == 0) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, first_name, last_name, applied_position, status, expected_start_date, notes," +
                " email_personal, created_at FROM [dbo].[candidates] WHERE id = ?", id);

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
                status
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
        return ((Number) v).longValue();
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
