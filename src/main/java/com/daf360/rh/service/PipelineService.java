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
    private final DemandFillService demandFillService;
    private final com.daf360.rh.security.TenantService tenantService;

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
            ep.gender, COALESCE(ctv.value_code, ep.contract_type) AS contract_type, ep.onboarding_completed,
            rd.budget_range, rd.technical_skills,
            c.recruitment_demand_id AS demand_id,
            -- jobExactTitle when set, else the generic title: the same precedence
            -- CandidateService.demandTitle uses, so a card and a detail page never disagree.
            COALESCE(NULLIF(LTRIM(RTRIM(rd.job_exact_title)), ''), rd.job_title) AS demand_title,
            CONVERT(datetime2, ni.scheduled_at) AS next_interview_at, ni.location AS next_interview_location,
            itp.name AS next_interview_type, itp.name_en AS next_interview_type_en,
            ipv.status AS prov_status,
            jo.asked_salary AS offer_asked_salary, jo.proposed_salary AS offer_salary,
            jo.expiry_date AS offer_expiry, jo.status AS offer_status,
            jo.notice_period_days AS offer_notice_period_days,
            jo.proposed_by AS offer_proposed_by, jo.round_number AS offer_round,
            iv.interviews_total, iv.interviews_filed, iv.interviews_validated,
            g.notice_period_days  AS grade_notice_period_days,
            -- Salary currency of the candidate's country: its DEVISE payroll parameter,
            -- else derived from the country ISO code (see currencyOf).
            dv.valeur AS pays_devise, p.iso_code AS pays_iso,
            -- Every post of the demand is filled: a candidature still in recruitment can no longer
            -- be hired and is archived on its creation date + N days (DemandFillService).
            CASE WHEN rd.statut = 'CLOTUREE' AND rd.closed_reason = 'AUTO_FILLED'
                      AND c.status IN ('PENDING','ACCEPTED','OFFER_SENT')
                 THEN CONVERT(date, DATEADD(day, """ + DemandFillService.ARCHIVE_AFTER_DAYS + """
            , c.created_at)) END AS archive_due_date
            """;

    /** FROM + joins: employee profile, linked demand, IT provisioning and next planned interview. */
    private static final String KANBAN_FROM = """
             FROM [dbo].[candidates] c
             LEFT JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id AND ep.deleted = 0
             -- contract_type stores the CONTRACT_TYPE row id (ContractTypeRefs); the card shows its code.
             LEFT JOIN [dbo].[configurable_list_values] ctv ON ctv.id = TRY_CAST(ep.contract_type AS BIGINT)
             LEFT JOIN [dbo].[recruitment_demands] rd ON rd.id = c.recruitment_demand_id
             LEFT JOIN [dbo].[it_provisioning] ipv ON ipv.candidate_id = c.id
             OUTER APPLY (
                 SELECT TOP 1 ci.scheduled_at, ci.interview_type_id, ci.location
                 FROM [dbo].[candidate_interviews] ci
                 WHERE ci.candidate_id = c.id AND ci.status = 'PLANNED' AND ci.scheduled_at >= GETDATE()
                 ORDER BY ci.scheduled_at ASC
             ) ni
             LEFT JOIN [dbo].[interview_types] itp ON itp.id = ni.interview_type_id
             -- The candidate's CURRENT offer round (V98), not a plain join.
             --
             -- job_offers used to hold one row per candidate, so a LEFT JOIN was safe. It now
             -- holds one row per negotiation round, and that same join would multiply the
             -- candidate: a card per round, the same person three times in one kanban column.
             -- OUTER APPLY keeps it at one row, the same shape the next-interview lookup above
             -- already uses.
             --
             -- `superseded_at IS NULL` is the current round; the ORDER BY is a belt-and-braces
             -- tiebreak, since nothing should ever leave two rounds unsuperseded.
             OUTER APPLY (
                 SELECT TOP 1 jo2.asked_salary, jo2.proposed_salary, jo2.expiry_date,
                        jo2.status, jo2.notice_period_days, jo2.proposed_by, jo2.round_number
                 FROM [dbo].[job_offers] jo2
                 WHERE jo2.candidate_id = c.id AND jo2.superseded_at IS NULL
                 ORDER BY jo2.round_number DESC, jo2.id DESC
             ) jo
             -- Interview progress for the card's « étape actuelle » (same rule as the rail on
             -- the candidate page): live interviews, those with a PV in, those RH validated.
             OUTER APPLY (
                 SELECT COUNT(*) AS interviews_total,
                        SUM(CASE WHEN ci2.status = 'DONE' THEN 1 ELSE 0 END)     AS interviews_filed,
                        SUM(CASE WHEN ci2.result IS NOT NULL THEN 1 ELSE 0 END) AS interviews_validated
                 FROM [dbo].[candidate_interviews] ci2
                 WHERE ci2.candidate_id = c.id AND ci2.status <> 'CANCELLED'
             ) iv
             LEFT JOIN [dbo].[grades] g ON g.id = c.applied_grade_id
             LEFT JOIN [dbo].[pays] p ON p.id = c.pays_id
             OUTER APPLY (
                 SELECT TOP 1 ps.valeur
                 FROM [dbo].[parameter_sets] ps
                 WHERE ps.pays_id = c.pays_id AND ps.cle = 'DEVISE'
             ) dv
            """;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Statuses considered "active" (in-pipeline, not yet hired or rejected). */
    private static final String ACTIVE_IN = "'PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED'";

    /** Statuses shown in the Kanban (all except ARCHIVED). */
    private static final String KANBAN_IN = "'PENDING','ACCEPTED','OFFER_SENT','HR_IN_PROGRESS','IT_IN_PROGRESS','EMAIL_RECEIVED','HIRED','REJECTED'";

    // ── Entity scope ───────────────────────────────────────────────────────────

    /**
     * {@code " AND <column> IN (1,2)"} for the caller's entities, or "" when they see every
     * entity (admin, mode ALL) or their scope is unresolved — the same rule as every other RH
     * list (TenantService.getPaysScope). Every read of this service used to return all
     * entities' candidates: a Tunisian recruiter saw the Egyptian pipeline.
     *
     * <p>The ids are Longs from the token, so they are inlined rather than bound — the text
     * blocks below stay readable and nothing user-typed reaches the SQL.
     */
    private String scopeFilter(String column) {
        var scope = tenantService.getPaysScope();
        if (scope.unfiltered()) return "";
        if (scope.paysIds().isEmpty()) return " AND 1 = 0";
        return " AND " + column + " IN (" + scope.paysIds().stream()
                .map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }

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
            """ + scopeFilter("pays_id");

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
            """ + scopeFilter("c.pays_id") + """

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
            """ + scopeFilter("pays_id") + """

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
            """ + scopeFilter("pays_id") + """

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
                " WHERE c.status IN (" + KANBAN_IN + ")" + scopeFilter("c.pays_id") + " ORDER BY c.created_at DESC";

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

    // ── Archived (V137) ────────────────────────────────────────────────────────

    /**
     * Every ARCHIVED candidature, newest first, with the reason — the board's « Archivés »
     * column. Scoped to the caller's entity like the candidate list (an admin sees all).
     * Capped: an archive grows forever and the board is not the place to page through it.
     */
    public List<com.daf360.rh.dto.pipeline.ArchivedCandidateDto> getArchived() {
        String sql = """
            SELECT TOP 300
                   c.id, c.first_name, c.last_name, c.gender, c.applied_position, c.email_personal,
                   c.recruitment_demand_id AS demand_id,
                   COALESCE(NULLIF(LTRIM(RTRIM(rd.job_exact_title)), ''), rd.job_title) AS demand_title,
                   c.archive_reason, c.archive_note, c.archived_from_status, c.rejection_reason,
                   CONVERT(datetime2, COALESCE(c.archived_at, c.updated_at, c.created_at)) AS archived_at,
                   u.fullName AS archived_by_name
              FROM [dbo].[candidates] c
              LEFT JOIN [dbo].[recruitment_demands] rd ON rd.id = c.recruitment_demand_id
              LEFT JOIN [dbo].[Users] u ON u.id = c.archived_by
             WHERE c.status = 'ARCHIVED'
            """ + scopeFilter("c.pays_id") + """

              ORDER BY COALESCE(c.archived_at, c.updated_at, c.created_at) DESC
            """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        return rows.stream().map(r -> {
            String first = str(r, "first_name");
            String last  = str(r, "last_name");
            return new com.daf360.rh.dto.pipeline.ArchivedCandidateDto(
                    toLong(r.get("id")),
                    ((first != null ? first : "") + " " + (last != null ? last : "")).trim(),
                    str(r, "gender"),
                    str(r, "applied_position"),
                    r.get("demand_id") != null ? toLong(r.get("demand_id")) : null,
                    str(r, "demand_title"),
                    str(r, "archive_reason"),
                    str(r, "archive_note"),
                    str(r, "archived_from_status"),
                    toIsoLocal(r.get("archived_at")),
                    str(r, "archived_by_name"),
                    str(r, "rejection_reason"),
                    str(r, "email_personal"));
        }).toList();
    }

    // ── Paginated list ─────────────────────────────────────────────────────────

    public Page<KanbanCandidateDto> getCandidatesPaged(String stage, String search, Pageable pageable) {
        String inClause = resolveInClause(stage);
        boolean hasSearch = search != null && !search.isBlank();

        StringBuilder where = new StringBuilder("WHERE c.status IN (").append(inClause).append(")")
                .append(scopeFilter("c.pays_id"));
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
        return moveToStage(id, stage, null);
    }

    @Transactional
    public KanbanCandidateDto moveToStage(Long id, String stage, Long actorUserId) {
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
            // Scoped like the board: a candidate of another entity is « not found » here.
            current = jdbc.queryForObject(
                    "SELECT status FROM [dbo].[candidates] c WHERE c.id = ?" + scopeFilter("c.pays_id"),
                    String.class, id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }
        if (!newStatus.equals(current)
                && !ALLOWED_TRANSITIONS.getOrDefault(current, java.util.Set.of()).contains(newStatus)) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Transition interdite : " + current + " → " + newStatus);
        }

        Long demandId = jdbc.queryForObject(
                "SELECT recruitment_demand_id FROM [dbo].[candidates] WHERE id = ?", Long.class, id);

        // V137: entering a filled post (offer agreed) needs a free one on the demand.
        if (!newStatus.equals(current)
                && DemandFillService.FILLING.contains(newStatus) && !DemandFillService.FILLING.contains(current)) {
            demandFillService.requireOpenPost(demandId);
        }

        int updated;
        if ("ARCHIVED".equals(newStatus) && !"ARCHIVED".equals(current)) {
            // Archived from the board: say so, and keep the status it had — an archived HIRED
            // recruit still occupies its post (DemandFillService counts archived_from_status).
            updated = jdbc.update("""
                    UPDATE [dbo].[candidates]
                       SET status = 'ARCHIVED', archived_from_status = status,
                           archived_at = SYSDATETIMEOFFSET(), archived_by = ?,
                           archive_reason = 'MANUAL', updated_at = SYSDATETIMEOFFSET()
                     WHERE id = ?""", actorUserId, id);
        } else {
            updated = jdbc.update(
                    "UPDATE [dbo].[candidates] SET status = ?, updated_at = SYSDATETIMEOFFSET() WHERE id = ?",
                    newStatus, id);
        }

        if (updated == 0) {
            throw new AppException(ErrorCode.CANDIDATE_NOT_FOUND, "Candidat introuvable : " + id);
        }

        if (!newStatus.equals(current)) {
            demandFillService.recomputeFor(demandId, actorUserId);
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
        String currency = currencyOf(str(r, "pays_devise"), str(r, "pays_iso"));
        String askedSalary = formatSalary(r.get("offer_asked_salary"), currency);
        String proposedSalary = formatSalary(r.get("offer_salary"), currency);
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
                offerStatus,
                toLong(r.get("demand_id")),
                str(r, "demand_title"),
                toIntOrNull(r.get("offer_notice_period_days")),
                toIntOrNull(r.get("grade_notice_period_days")),
                toIsoLocal(r.get("next_interview_at")),
                str(r, "next_interview_type"),
                str(r, "next_interview_type_en"),
                (int) toLong(r.get("interviews_total")),
                (int) toLong(r.get("interviews_filed")),
                (int) toLong(r.get("interviews_validated")),
                str(r, "offer_proposed_by"),
                toIntOrNull(r.get("offer_round")),
                str(r, "prov_status"),
                r.get("archive_due_date") != null ? r.get("archive_due_date").toString().substring(0, 10) : null
        );
    }

    /** ISO local date-time (no zone) so the front formats it in the UI language. */
    private static String toIsoLocal(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toString();
        if (v instanceof OffsetDateTime odt) return odt.toLocalDateTime().toString();
        return null;
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

    /**
     * Formats a numeric salary as a compact French label in the given currency,
     * e.g. {@code 65000, "TND" → "65 000 DT"}, {@code 65000, "EGP" → "65 000 EGP"}.
     */
    private static String formatSalary(Object v, String currency) {
        if (v == null) return null;
        if (v instanceof Number n) {
            String unit = "TND".equals(currency) ? "DT" : currency;
            return String.format(Locale.FRENCH, "%,.0f %s", n.doubleValue(), unit);
        }
        return v.toString();
    }

    /**
     * ISO 4217 currency of a candidate's country: the country's DEVISE payroll parameter
     * when set, else derived from its ISO code (same mapping as PdfDocumentService), TND by default.
     */
    private static String currencyOf(String devise, String iso) {
        if (devise != null && !devise.isBlank()) return devise.trim().toUpperCase();
        if (iso == null) return "TND";
        return switch (iso.trim().toUpperCase()) {
            case "EG" -> "EGP";
            default   -> "TND";
        };
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
