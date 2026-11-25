package com.daf360.rh.history;

import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * « Historique » tab of /rh/offboarding (and of each /rh/offboarding/type/:reason): every
 * action on every departure file of the caller's entities, newest first, with who took it.
 *
 * Read from the action log, which every offboarding mutation has always written to — the
 * same source as a file's own « Historique » drawer (getAuditTrail), but across files. A
 * file's lines are logged against six entity types (the file, its tasks, asset returns,
 * checklist items, settlement lines, exit interview); each is linked back to its file
 * through its {@code workflow_instance_id}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OffboardingHistoryService {

    private final JdbcTemplate   jdbc;
    private final HistorySupport support;
    private final TenantService  tenantService;

    /** Entity type of the log → (table, category) of the line. */
    private record Source(String entityType, String table, String category) {}

    private static final List<Source> SOURCES = List.of(
            new Source("OffboardingTask",          "offboarding_tasks",           "TASK"),
            new Source("OffboardingAssetReturn",   "offboarding_asset_returns",   "ASSET"),
            new Source("OffboardingChecklistItem", "offboarding_checklist_items", "CHECKLIST"),
            new Source("OffboardingSettlementLine","offboarding_settlement_lines","SETTLEMENT"),
            new Source("ExitInterview",            "exit_interviews",             "INTERVIEW"));

    /** The outcome an action gives the file or its item — the « Statut » column. */
    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("OFFBOARDING_STARTED",            "STARTED"),
            Map.entry("OFFBOARDING_MANAGER_VALIDATED",  "MANAGER_VALIDATED"),
            Map.entry("OFFBOARDING_HR_VALIDATED",       "HR_VALIDATED"),
            Map.entry("OFFBOARDING_TASK_DONE",          "DONE"),
            Map.entry("OFFBOARDING_TASK_SKIPPED",       "SKIPPED"),
            Map.entry("ASSET_RETURN_CONFIRMED",         "RETURNED"),
            Map.entry("EXIT_INTERVIEW_SCHEDULED",       "PLANNED"),
            Map.entry("EXIT_INTERVIEW_SAVED",           "DONE"),
            Map.entry("OFFBOARDING_VALIDATED",          "VALIDATED"),
            Map.entry("OFFBOARDING_CANCELLED",          "CANCELLED"),
            Map.entry("OFFBOARDING_REOPENED",           "REOPENED"),
            Map.entry("OFFBOARDING_ARCHIVED",           "ARCHIVED"));

    /** Ids and file paths the log carries — meaningless on screen. */
    private static final Set<String> TECHNICAL_KEYS = Set.of(
            "profileId", "workflowId", "workflowInstanceId", "managerProfileId", "file", "pvFile", "userId");

    /**
     * @param reason departure reason code (the /type/:reason pages), null = every reason
     */
    public List<HistoryEntryDto> history(String reason, LocalDate from, LocalDate to) {
        HistorySupport.Window w = HistorySupport.window(from, to);
        String scope = HistorySupport.paysFilter(tenantService.getPaysScope(), "wi.pays_id");
        String reasonSql = reason != null && !reason.isBlank() ? " AND wi.departure_reason = ?" : "";

        String cols = """
                al.id, CAST(al.userId AS NVARCHAR(200)) AS actor, al.action, al.entityType,
                CAST(al.newValue AS NVARCHAR(MAX)) AS new_value,
                CAST(SWITCHOFFSET(al.[timestamp], '+00:00') AS DATETIME2) AS ts,
                wi.id AS wf_id, wi.employee_profile_id, wi.departure_reason""";
        String period = " AND al.[timestamp] >= ? AND al.[timestamp] < ?";

        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(cols).append(", 'FILE' AS category")
                .append(" FROM [dbo].[audit_log] al")
                .append(" JOIN [dbo].[offboarding_workflow_instances] wi ON al.entityId = CAST(wi.id AS NVARCHAR(100))")
                .append(" WHERE al.entityType = 'OffboardingWorkflowInstance'")
                .append(period).append(scope).append(reasonSql);
        for (Source s : SOURCES) {
            sql.append(" UNION ALL SELECT ").append(cols).append(", '").append(s.category()).append("' AS category")
               .append(" FROM [dbo].[audit_log] al")
               .append(" JOIN [dbo].[").append(s.table()).append("] x ON al.entityId = CAST(x.id AS NVARCHAR(100))")
               .append(" JOIN [dbo].[offboarding_workflow_instances] wi ON wi.id = x.workflow_instance_id")
               .append(" WHERE al.entityType = '").append(s.entityType()).append("'")
               .append(period).append(scope).append(reasonSql);
        }

        List<Object> params = new ArrayList<>();
        for (int i = 0; i <= SOURCES.size(); i++) {
            params.add(w.from());
            params.add(w.to());
            if (!reasonSql.isEmpty()) params.add(reason);
        }

        record Row(long id, LocalDateTime at, Long actor, String action, String category, String newValue,
                   long wfId, Long profileId, String reason) {}

        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> {
            Timestamp t = rs.getTimestamp("ts");
            long p = rs.getLong("employee_profile_id");
            return new Row(rs.getLong("id"), t != null ? t.toLocalDateTime() : null,
                    parseLong(rs.getString("actor")), rs.getString("action"), rs.getString("category"),
                    rs.getString("new_value"), rs.getLong("wf_id"), rs.wasNull() ? null : p,
                    rs.getString("departure_reason"));
        }, params.toArray());

        List<Row> kept = rows.stream()
                .filter(r -> w.contains(r.at()))
                .sorted(Comparator.comparing(Row::at).reversed().thenComparing(Row::id, Comparator.reverseOrder()))
                .limit(HistorySupport.MAX_ROWS)
                .toList();

        Map<Long, String> actors = support.userNames(kept.stream().map(Row::actor).filter(Objects::nonNull).toList());
        Map<Long, HistorySupport.EmployeeRef> employees =
                support.employees(kept.stream().map(Row::profileId).filter(Objects::nonNull).toList());

        return kept.stream().map(r -> {
            Map<String, String> facts = HistorySupport.facts(r.newValue());
            TECHNICAL_KEYS.forEach(facts::remove);
            facts.remove("reason");      // departure type: shown as the subject's subtitle
            String comment = null;
            if ("OFFBOARDING_TASK_SKIPPED".equals(r.action()) || "OFFBOARDING_CANCELLED".equals(r.action())) {
                comment = HistorySupport.facts(r.newValue()).get("reason");
            }
            if ("OFFBOARDING_REOPENED".equals(r.action())) {
                // Logged as "IN_PROGRESS | <reason>": the reason is the comment.
                String raw = r.newValue() == null ? "" : r.newValue();
                int bar = raw.indexOf('|');
                comment = bar >= 0 ? raw.substring(bar + 1).trim() : null;
                facts.clear();
            }
            if ("OFFBOARDING_ARCHIVED".equals(r.action())) facts.clear();
            HistorySupport.EmployeeRef e = r.profileId() != null ? employees.get(r.profileId()) : null;
            return HistoryEntryDto.builder()
                    .id("log-" + r.id())
                    .timestamp(r.at().toInstant(ZoneOffset.UTC).toString())
                    .category(r.category())
                    .action(r.action())
                    .actorId(r.actor())
                    .actorName(r.actor() != null ? actors.get(r.actor()) : null)
                    .status(STATUS.get(r.action()))
                    .comment(comment == null || comment.isBlank() ? null : comment)
                    .details(facts.isEmpty() ? null : facts)
                    .subjectId(r.wfId())
                    .subjectName(e != null ? e.name() : null)
                    .subjectSubtitle(r.reason())
                    .subjectProfileId(r.profileId())
                    .subjectPhotoUrl(e != null ? e.photoUrl() : null)
                    .subjectGender(e != null ? e.gender() : null)
                    .build();
        }).toList();
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
