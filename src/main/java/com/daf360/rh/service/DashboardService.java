package com.daf360.rh.service;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.common.PreviewResponse;
import com.daf360.rh.dto.dashboard.*;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final EmployeeProfileRepository profileRepository;
    private final CandidateRepository       candidateRepository;
    private final JdbcTemplate              jdbcTemplate;

    // ── ① /stats ─────────────────────────────────────────────────────────────

    public DashboardStatsDto getStats() {
        LocalDate today     = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);

        long totalActifs = profileRepository.countByLifecycleStatus(LifecycleStatus.ACTIVE);
        long onLeave     = profileRepository.countByLifecycleStatus(LifecycleStatus.ON_LEAVE);

        Long newThisMonthRaw = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[employee_profiles] " +
                "WHERE hire_date >= ? AND deleted = 0",
                Long.class, Date.valueOf(monthStart));
        long newThisMonth = newThisMonthRaw != null ? newThisMonthRaw : 0L;

        Long pendingRaw = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[employee_requests] WHERE status = 'SUBMITTED'",
                Long.class);
        long pendingRequests = pendingRaw != null ? pendingRaw : 0L;

        long contratsARenouveler = profileRepository.countContractsEndingSoon(
                today, today.plusDays(60), LifecycleStatus.ACTIVE);

        // Gender percentages — reuse workforce logic
        WorkforceStatsDto ws = getWorkforceStats();

        return new DashboardStatsDto(
                totalActifs,
                newThisMonth,
                onLeave,
                pendingRequests,
                ws.pctFemmes(),
                ws.pctHommes(),
                0L,           // collaborateursSansManager — no manager concept yet
                contratsARenouveler);
    }

    // ── ② /workforce ─────────────────────────────────────────────────────────

    public WorkforceStatsDto getWorkforceStats() {
        long total = profileRepository.countByLifecycleStatus(LifecycleStatus.ACTIVE);
        if (total == 0) return new WorkforceStatsDto(0, 0, 0, 0, 0.0, 0.0);

        Map<String, Long> byGender = profileRepository
                .countByGenderAndLifecycleStatus(LifecycleStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(
                        row -> row[0] != null ? row[0].toString().toUpperCase() : "NON_DEFINI",
                        row -> ((Number) row[1]).longValue(),
                        Long::sum));

        long h = byGender.getOrDefault("MALE",   byGender.getOrDefault("MASCULIN", 0L));
        long f = byGender.getOrDefault("FEMALE", byGender.getOrDefault("FEMININ",  0L));
        long n = Math.max(0L, total - h - f);

        return new WorkforceStatsDto(
                total, h, f, n,
                Math.round((double) h / total * 1000.0) / 10.0,
                Math.round((double) f / total * 1000.0) / 10.0);
    }

    // ── ③ /completion ────────────────────────────────────────────────────────

    public ProfileCompletionDto getCompletion() {
        long done  = profileRepository.countByOnboardingCompletedTrue();
        long total = profileRepository.count();
        long todo  = total - done;
        double rate = total == 0 ? 0.0 : Math.round((double) done / total * 1000.0) / 10.0;
        return new ProfileCompletionDto(rate, done, todo);
    }

    // ── ④ /fin-periode-essai ─────────────────────────────────────────────────

    public PreviewResponse<ProbationAlertDto> getProbationAlerts(int joursMax) {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(joursMax);

        List<ProbationAlertDto> items = jdbcTemplate.query(
                "SELECT TOP 2 ep.id, u.fullName, ep.photo_url, ep.probation_end_date, " +
                "       ep.contract_end_date, ep.gender, " +
                "       d.label_fr AS department_label, r.frenchName AS role_name " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "LEFT JOIN [dbo].[departments] d ON d.id = ep.department_id " +
                "LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id " +
                "WHERE ep.is_on_probation = 1 " +
                "  AND ep.probation_end_date >= ? " +
                "  AND ep.probation_end_date <= ? " +
                "  AND ep.deleted = 0 " +
                "ORDER BY ep.probation_end_date ASC",
                (rs, rowNum) -> {
                    Date d = rs.getDate("probation_end_date");
                    LocalDate endDate = d != null ? d.toLocalDate() : today;
                    Date ced = rs.getDate("contract_end_date");
                    LocalDate contractEnd = ced != null ? ced.toLocalDate() : null;
                    return new ProbationAlertDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("photo_url"),
                            endDate,
                            ChronoUnit.DAYS.between(today, endDate),
                            contractEnd,
                            rs.getString("department_label"),
                            rs.getString("role_name"),
                            rs.getString("gender"));
                },
                Date.valueOf(today),
                Date.valueOf(limit));

        long total = items.isEmpty() ? 0L
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM [dbo].[employee_profiles] " +
                        "WHERE is_on_probation = 1 AND probation_end_date >= ? " +
                        "  AND probation_end_date <= ? AND deleted = 0",
                        Long.class, Date.valueOf(today), Date.valueOf(limit));

        return new PreviewResponse<>(items, total);
    }

    // ── ⑤ /anniversaires ─────────────────────────────────────────────────────

    public List<AnniversaireDto> getAnniversaires(int ignored) {
        LocalDate today = LocalDate.now();

        return jdbcTemplate.query(
                "SELECT ep.id, u.fullName, ep.photo_url, ep.date_of_birth " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.date_of_birth IS NOT NULL " +
                "  AND ep.lifecycle_status = 'ACTIVE' " +
                "  AND ep.deleted = 0 " +
                "  AND MONTH(ep.date_of_birth) = MONTH(GETDATE()) " +
                "ORDER BY DAY(ep.date_of_birth) ASC",
                (rs, rowNum) -> {
                    Date d = rs.getDate("date_of_birth");
                    LocalDate dob = d != null ? d.toLocalDate() : null;
                    int joursAvant = 0;
                    if (dob != null) {
                        LocalDate next = dob.withYear(today.getYear());
                        if (next.isBefore(today)) next = next.plusYears(1);
                        joursAvant = (int) ChronoUnit.DAYS.between(today, next);
                    }
                    return new AnniversaireDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("photo_url"),
                            dob,
                            joursAvant);
                });
    }

    // ── ⑥ /nouveaux-employes ─────────────────────────────────────────────────

    public List<NouvelEmployeDto> getNouveauxEmployes(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LocalDate threeMonthsAgo = LocalDate.now().minusMonths(3);

        return jdbcTemplate.query(
                "SELECT ep.id, u.fullName, ep.hire_date, ep.gender, ep.contract_type, " +
                "       ep.onboarding_completed, " +
                "       d.label_fr AS department_label, g.label_fr AS grade_label, " +
                "       disc.label_fr AS discipline_label, p.french_label AS pays_label " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "LEFT JOIN [dbo].[departments]  d    ON d.id    = ep.department_id " +
                "LEFT JOIN [dbo].[grades]        g    ON g.id    = ep.grade_id " +
                "LEFT JOIN [dbo].[disciplines]   disc ON disc.id = ep.discipline_id " +
                "LEFT JOIN [dbo].[pays]          p    ON p.id    = ep.pays_id " +
                "WHERE ep.lifecycle_status = 'ACTIVE' " +
                "  AND ep.deleted = 0 " +
                "  AND ep.hire_date >= ? " +
                "ORDER BY ep.hire_date DESC " +
                "OFFSET 0 ROWS FETCH NEXT " + safeLimit + " ROWS ONLY",
                (rs, rowNum) -> {
                    long pid = rs.getLong("id");
                    Date d   = rs.getDate("hire_date");
                    return new NouvelEmployeDto(
                            pid,
                            rs.getString("fullName"),
                            "/api/hr/profiles/" + pid + "/photo",
                            d != null ? d.toLocalDate() : null,
                            rs.getString("department_label"),
                            rs.getString("grade_label"),
                            rs.getString("gender"),
                            rs.getBoolean("onboarding_completed"),
                            rs.getString("pays_label"),
                            rs.getString("discipline_label"),
                            rs.getString("contract_type"));
                },
                Date.valueOf(threeMonthsAgo));
    }

    // ── /missing-documents ────────────────────────────────────────────────────
    // Required doc types every active profile should have

    public PreviewResponse<MissingDocumentDto> getMissingDocuments() {
        String fromWhere =
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "CROSS JOIN (VALUES ('CONTRACT'),('ID_CARD'),('RIB')) AS req(doc_type) " +
                "WHERE ep.lifecycle_status = 'ACTIVE' " +
                "  AND ep.deleted = 0 " +
                "  AND NOT EXISTS ( " +
                "    SELECT 1 FROM [dbo].[employee_documents] ed " +
                "    WHERE ed.employee_profile_id = ep.id " +
                "      AND ed.document_type = req.doc_type " +
                "      AND ed.verification_status <> 'REJECTED' " +
                "  ) ";

        // Count distinct employees (not doc-type rows)
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ep.id) " + fromWhere, Long.class);

        // Flat rows ordered by urgency desc so most-urgent employees come first
        record FlatRow(long pid, String fullName, String docType, int months) {}
        List<FlatRow> rows = jdbcTemplate.query(
                "SELECT ep.id AS profile_id, u.fullName, req.doc_type, " +
                "       DATEDIFF(month, ep.hire_date, GETDATE()) AS months_since_hire " +
                fromWhere +
                "ORDER BY months_since_hire DESC, ep.id ASC",
                (rs, rowNum) -> new FlatRow(
                        rs.getLong("profile_id"),
                        rs.getString("fullName"),
                        rs.getString("doc_type"),
                        rs.getInt("months_since_hire")));

        // Group by employee, preserving urgency order; take first 2 employees
        LinkedHashMap<Long, MissingDocumentDto> grouped = new LinkedHashMap<>();
        for (FlatRow row : rows) {
            grouped.computeIfAbsent(row.pid(), k -> {
                String urgency = row.months() >= 3 ? "HIGH" : row.months() >= 1 ? "MEDIUM" : "LOW";
                return new MissingDocumentDto(row.pid(), row.fullName(), new ArrayList<>(), urgency);
            }).missingDocs().add(row.docType());
        }

        List<MissingDocumentDto> items = grouped.values().stream()
                .limit(2)
                .collect(Collectors.toList());

        return new PreviewResponse<>(items, total != null ? total : 0L);
    }

    // ── Page 1 ────────────────────────────────────────────────────────────────

    public WeeklyStatsDto getWeeklyStats() {
        int week = LocalDate.now().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return new WeeklyStatsDto(0L, 0.0, "Semaine " + week);
    }

    public RecruitmentStatsDto getRecruitmentStats() {
        List<CandidateStatus> activeStatuses = List.of(
                CandidateStatus.PENDING,
                CandidateStatus.ACCEPTED,
                CandidateStatus.IT_IN_PROGRESS,
                CandidateStatus.EMAIL_RECEIVED,
                CandidateStatus.HR_IN_PROGRESS);

        long recrutements = candidateRepository.countByStatusIn(activeStatuses);

        LocalDate now = LocalDate.now();
        Long congesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[absences] WHERE etat_demande = 'VALIDE'" +
                " AND YEAR(date_debut) = ? AND MONTH(date_debut) = ?",
                Long.class, now.getYear(), now.getMonthValue());
        long conges = congesCount != null ? congesCount : 0L;

        return new RecruitmentStatsDto(recrutements, conges);
    }

    public List<RecentActivityDto> getRecentActivity(int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        return jdbcTemplate.query(
                "SELECT TOP " + safeSize + " ep.id, u.fullName, ep.updated_at " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.deleted = 0 " +
                "ORDER BY ep.updated_at DESC",
                (rs, rowNum) -> {
                    java.sql.Timestamp ts = rs.getTimestamp("updated_at");
                    LocalDate date = ts != null ? ts.toLocalDateTime().toLocalDate() : LocalDate.now();
                    return new RecentActivityDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            "Profil mis à jour",
                            date,
                            "profil");
                });
    }
}
