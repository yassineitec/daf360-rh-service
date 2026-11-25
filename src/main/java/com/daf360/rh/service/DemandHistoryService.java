package com.daf360.rh.service;

import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.dto.recruitment.DemandHistoryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The history of one recruitment demand, assembled from what is already recorded — no table
 * of its own:
 * <ul>
 *   <li>the demand's audit trail (audit_log, entity RECRUITMENT_DEMAND): creation, review,
 *       cancel, headcount changes, manual / automatic close and reopen;</li>
 *   <li>the demand row itself, as a fallback when those audit lines are missing (rows created
 *       before the audit existed, or by a script): submitted, reviewed, closed;</li>
 *   <li>its candidatures: each one joining (and whether it was COPIED from an archived
 *       candidature — the audit of its creation says so), its offer agreed (a post filled),
 *       its archiving (with the reason) or refusal;</li>
 *   <li>the other demands for the same post (department + title + exact title, the job
 *       library's rule): this post was requested before, or again after.</li>
 * </ul>
 * Newest first. The caller (RecruitmentDemandService) has already checked the scope.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemandHistoryService {

    private final JdbcTemplate jdbc;

    public List<DemandHistoryEvent> history(RecruitmentDemand d, List<RecruitmentDemand> samePost) {
        List<Row> rows = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        Function<Object, String> nameOf = id -> {
            Long uid = toLong(id);
            if (uid == null) return null;
            return names.computeIfAbsent(uid, this::userName);
        };

        // ── 1. The demand's own audit trail ────────────────────────────────────
        Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> a : jdbc.queryForList("""
                SELECT a.action, a.userId, a.oldValue, a.newValue,
                       CONVERT(datetime2, SWITCHOFFSET(a.[timestamp], '+00:00')) AS ts
                  FROM [dbo].[audit_log] a
                 WHERE a.entityType = 'RECRUITMENT_DEMAND' AND a.entityId = ?
                """, String.valueOf(d.getId()))) {
            String action = str(a.get("action"));
            Map<String, String> before = kv(str(a.get("oldValue")));
            Map<String, String> after  = kv(str(a.get("newValue")));
            LocalDateTime ts = ldt(a.get("ts"));
            String actor = "SYSTEM".equals(str(a.get("userId"))) ? null : nameOf.apply(a.get("userId"));
            if (action == null || ts == null) continue;
            seen.add(action);
            switch (action) {
                case "CREATE" -> rows.add(new Row(ts, "CREATED", actor, null, null, Map.of()));
                case "REVIEW" -> rows.add(new Row(ts,
                        "APPROUVEE".equals(after.get("statut")) ? "APPROVED" : "REJECTED", actor, null, null, Map.of()));
                case "CANCEL" -> rows.add(new Row(ts, "CANCELLED", actor, null, null, Map.of()));
                case "CHANGE_HEADCOUNT" -> rows.add(new Row(ts, "HEADCOUNT_CHANGED", actor, null, null,
                        details("from", before.get("headcount"), "to", after.get("headcount"))));
                case "CLOSE" -> rows.add(new Row(ts, "CLOSED_MANUAL", actor, null, null,
                        details("note", after.get("note"), "archived", after.get("archived"))));
                case "AUTO_CLOSE" -> rows.add(new Row(ts, "CLOSED_FILLED", null, null, null,
                        details("filled", after.get("filled"), "archived", after.get("archived"))));
                case "REOPEN" -> rows.add(new Row(ts, "REOPENED", actor, null, null, details("filled", after.get("filled"))));
                case "AUTO_REOPEN" -> rows.add(new Row(ts, "REOPENED_AUTO", null, null, null, details("filled", after.get("filled"))));
                default -> { /* other actions are not part of this story */ }
            }
        }

        // ── 2. The demand row, when its audit line is missing ──────────────────
        if (!seen.contains("CREATE") && d.getSubmittedAt() != null) {
            rows.add(new Row(utc(d.getSubmittedAt()), "CREATED", nameOf.apply(d.getCreatedByUserId()), null, null, Map.of()));
        }
        if (!seen.contains("REVIEW") && d.getReviewedAt() != null
                && d.getStatut() != RecruitmentDemandStatus.EN_ATTENTE && d.getStatut() != RecruitmentDemandStatus.ANNULEE) {
            rows.add(new Row(utc(d.getReviewedAt()), d.getStatut() == RecruitmentDemandStatus.REJETEE ? "REJECTED" : "APPROVED",
                    nameOf.apply(d.getReviewedByUserId()), null, null,
                    details("comment", d.getReviewComment())));
        }
        if (!seen.contains("CLOSE") && !seen.contains("AUTO_CLOSE")
                && d.getStatut() == RecruitmentDemandStatus.CLOTUREE && d.getClosedAt() != null) {
            boolean manual = RecruitmentDemand.CLOSED_MANUAL.equals(d.getClosedReason());
            rows.add(new Row(utc(d.getClosedAt()), manual ? "CLOSED_MANUAL" : "CLOSED_FILLED",
                    manual ? nameOf.apply(d.getClosedBy()) : null, null, null,
                    details("filled", d.getPositionsFilled() + "/" + d.getHeadcount())));
        }

        // ── 3. The same post, requested before / again after ───────────────────
        LocalDateTime submitted = d.getSubmittedAt() != null ? utc(d.getSubmittedAt()) : null;
        List<RecruitmentDemand> earlier = samePost.stream()
                .filter(o -> !o.getId().equals(d.getId()) && o.getSubmittedAt() != null
                        && (d.getSubmittedAt() == null || o.getSubmittedAt().isBefore(d.getSubmittedAt())))
                .sorted(Comparator.comparing(RecruitmentDemand::getSubmittedAt).reversed())
                .toList();
        if (!earlier.isEmpty() && submitted != null) {
            RecruitmentDemand last = earlier.get(0);
            rows.add(new Row(submitted.plusNanos(1_000), "REQUESTED_AGAIN", null, null, null,
                    details("count", String.valueOf(earlier.size()), "demandId", String.valueOf(last.getId()),
                            "date", utc(last.getSubmittedAt()).toLocalDate().toString())));
        }
        for (RecruitmentDemand later : samePost) {
            if (later.getId().equals(d.getId()) || later.getSubmittedAt() == null || d.getSubmittedAt() == null
                    || !later.getSubmittedAt().isAfter(d.getSubmittedAt())) continue;
            rows.add(new Row(utc(later.getSubmittedAt()), "REQUESTED_LATER", nameOf.apply(later.getCreatedByUserId()), null, null,
                    details("demandId", String.valueOf(later.getId()), "status", later.getStatut().name())));
        }

        // ── 4. Its candidatures ────────────────────────────────────────────────
        List<Map<String, Object>> cands = jdbc.queryForList("""
                SELECT c.id, c.first_name, c.last_name, c.status, c.created_by, c.archive_reason,
                       c.archived_from_status, c.rejection_reason, c.archived_by,
                       CONVERT(datetime2, SWITCHOFFSET(c.created_at, '+00:00'))  AS created_at,
                       CONVERT(datetime2, SWITCHOFFSET(c.updated_at, '+00:00'))  AS updated_at,
                       CONVERT(datetime2, SWITCHOFFSET(c.archived_at, '+00:00')) AS archived_at
                  FROM [dbo].[candidates] c
                 WHERE c.recruitment_demand_id = ?
                """, d.getId());
        if (!cands.isEmpty()) {
            Map<Long, String> candName = new LinkedHashMap<>();
            for (Map<String, Object> c : cands) {
                candName.put(toLong(c.get("id")),
                        (nz(str(c.get("first_name"))) + " " + nz(str(c.get("last_name")))).trim());
            }
            String in = String.join(",", Collections.nCopies(candName.size(), "?"));
            Object[] ids = candName.keySet().toArray();

            // Which ones were copied from an archived candidature (audit of their creation).
            Map<Long, Long> copiedFrom = new HashMap<>();
            for (Map<String, Object> a : jdbc.queryForList(
                    "SELECT entityId, newValue FROM [dbo].[audit_log] WHERE entityType = 'CANDIDATE' AND action = 'CREATE' "
                    + "AND newValue LIKE '%copiedFrom=%' AND entityId IN (" + in + ")",
                    candName.keySet().stream().map(String::valueOf).toArray())) {
                Long src = toLong(kv(str(a.get("newValue"))).get("copiedFrom"));
                Long cid = toLong(a.get("entityId"));
                if (src != null && cid != null) copiedFrom.put(cid, src);
            }
            Map<Long, String> sourceName = new HashMap<>();
            for (Long src : copiedFrom.values()) {
                if (sourceName.containsKey(src)) continue;
                List<String> n = jdbc.queryForList(
                        "SELECT LTRIM(RTRIM(CONCAT(first_name, ' ', last_name))) FROM [dbo].[candidates] WHERE id = ?",
                        String.class, src);
                sourceName.put(src, n.isEmpty() ? null : n.get(0));
            }

            // Offers agreed — each one took a post.
            Map<Long, LocalDateTime> accepted = new HashMap<>();
            for (Map<String, Object> o : jdbc.queryForList(
                    "SELECT candidate_id, CONVERT(datetime2, SWITCHOFFSET(COALESCE(decided_at, updated_at), '+00:00')) AS ts "
                    + "FROM [dbo].[job_offers] WHERE status = 'ACCEPTED' AND candidate_id IN (" + in + ")", ids)) {
                Long cid = toLong(o.get("candidate_id"));
                LocalDateTime ts = ldt(o.get("ts"));
                if (cid != null && ts != null) accepted.merge(cid, ts, (x, y) -> x.isAfter(y) ? x : y);
            }

            for (Map<String, Object> c : cands) {
                Long cid = toLong(c.get("id"));
                String cname = candName.get(cid);
                LocalDateTime created = ldt(c.get("created_at"));
                if (created != null) {
                    Long src = copiedFrom.get(cid);
                    rows.add(src != null
                            ? new Row(created, "CANDIDATE_REUSED", nameOf.apply(c.get("created_by")), cid, cname,
                                      details("fromId", String.valueOf(src), "fromName", sourceName.get(src)))
                            : new Row(created, "CANDIDATE_ADDED", nameOf.apply(c.get("created_by")), cid, cname, Map.of()));
                }
                LocalDateTime acc = accepted.get(cid);
                if (acc != null) rows.add(new Row(acc, "OFFER_ACCEPTED", null, cid, cname, Map.of()));

                String status = str(c.get("status"));
                LocalDateTime archivedAt = ldt(c.get("archived_at"));
                if ("ARCHIVED".equals(status)) {
                    rows.add(new Row(archivedAt != null ? archivedAt : ldt(c.get("updated_at")), "CANDIDATE_ARCHIVED",
                            nameOf.apply(c.get("archived_by")), cid, cname,
                            details("reason", nz(str(c.get("archive_reason"))).isEmpty() ? "UNKNOWN" : str(c.get("archive_reason")),
                                    "from", str(c.get("archived_from_status")))));
                } else if ("REJECTED".equals(status)) {
                    rows.add(new Row(ldt(c.get("updated_at")), "CANDIDATE_REJECTED", null, cid, cname,
                            details("reason", str(c.get("rejection_reason")))));
                }
            }
        }

        return rows.stream()
                .filter(r -> r.at() != null)
                .sorted(Comparator.comparing(Row::at).reversed())
                .map(r -> new DemandHistoryEvent(r.at().toString() + "Z", r.type(), r.actor(), r.candidateId(),
                        r.candidateName(), r.details()))
                .collect(Collectors.toList());
    }

    private record Row(LocalDateTime at, String type, String actor, Long candidateId, String candidateName,
                       Map<String, String> details) {}

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String userName(Long id) {
        try {
            List<String> r = jdbc.queryForList(
                    "SELECT COALESCE(fullName, username, email) FROM [dbo].[Users] WHERE id = ?", String.class, id);
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** « k=v; k2=v2 » (the audit's own format) → map. */
    private static Map<String, String> kv(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) return m;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return m;
    }

    /** Detail map without null values (Map.of refuses them). */
    private static Map<String, String> details(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null && !kv[i + 1].isBlank()) m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static LocalDateTime utc(java.time.OffsetDateTime t) {
        return t.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDateTime ldt(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof LocalDateTime l) return l;
        return null;
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try { return Long.valueOf(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
