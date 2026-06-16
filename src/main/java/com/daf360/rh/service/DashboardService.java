package com.daf360.rh.service;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.dashboard.*;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final EmployeeProfileRepository profileRepository;
    private final CandidateRepository       candidateRepository;
    private final LeaveRequestRepository    leaveRequestRepository;
    private final JdbcTemplate              jdbcTemplate;

    // ── Page 2: Portail-RH ────────────────────────────────────────────────────

    public DashboardStatsDto getStats() {
        LocalDate today = LocalDate.now();
        long contrats = profileRepository.countContractsEndingSoon(
                today, today.plusDays(60), LifecycleStatus.ACTIVE);
        long actifs = profileRepository.countByLifecycleStatus(LifecycleStatus.ACTIVE);
        // TODO: entretiens and formations require dedicated domains
        return new DashboardStatsDto(0L, contrats, actifs, 0L, 0L);
    }

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

    public ProfileCompletionDto getCompletion() {
        long done  = profileRepository.countByOnboardingCompletedTrue();
        long total = profileRepository.count();
        long todo  = total - done;
        double rate = total == 0 ? 0.0 : Math.round((double) done / total * 1000.0) / 10.0;
        return new ProfileCompletionDto(rate, done, todo);
    }

    public List<ProbationAlertDto> getProbationAlerts(int joursMax) {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(joursMax);

        return jdbcTemplate.query(
                "SELECT ep.id, u.fullName, ep.photo_url, ep.probation_end_date " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE ep.is_on_probation = 1 " +
                "  AND ep.probation_end_date >= ? " +
                "  AND ep.probation_end_date <= ? " +
                "  AND ep.deleted = 0 " +
                "ORDER BY ep.probation_end_date ASC",
                (rs, rowNum) -> {
                    Date d = rs.getDate("probation_end_date");
                    LocalDate endDate = d != null ? d.toLocalDate() : today;
                    return new ProbationAlertDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("photo_url"),
                            endDate,
                            ChronoUnit.DAYS.between(today, endDate));
                },
                Date.valueOf(today),
                Date.valueOf(limit));
    }

    public List<AnniversaireDto> getAnniversaires(int mois) {
        LocalDate today = LocalDate.now();

        return jdbcTemplate.query(
                "SELECT ep.id, u.fullName, ep.photo_url, ep.date_of_birth " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "WHERE MONTH(ep.date_of_birth) = ? " +
                "  AND ep.lifecycle_status = 'ACTIVE' " +
                "  AND ep.deleted = 0 " +
                "  AND ep.date_of_birth IS NOT NULL " +
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
                },
                mois);
    }

    public List<NouvelEmployeDto> getNouveauxEmployes(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
                "SELECT ep.id, u.fullName, ep.photo_url, ep.hire_date, " +
                "       d.label_fr AS department_label, g.label_fr AS grade_label " +
                "FROM [dbo].[employee_profiles] ep " +
                "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                "LEFT JOIN [dbo].[departments] d ON d.id = ep.department_id " +
                "LEFT JOIN [dbo].[grades] g ON g.id = ep.grade_id " +
                "WHERE ep.lifecycle_status = 'ACTIVE' " +
                "  AND ep.deleted = 0 " +
                "ORDER BY ep.hire_date DESC " +
                "OFFSET 0 ROWS FETCH NEXT " + safeLimit + " ROWS ONLY",
                (rs, rowNum) -> {
                    Date d = rs.getDate("hire_date");
                    return new NouvelEmployeDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("photo_url"),
                            d != null ? d.toLocalDate() : null,
                            rs.getString("department_label"),
                            rs.getString("grade_label"));
                });
    }

    // ── Page 1: Tableau de bord RH ────────────────────────────────────────────

    public WeeklyStatsDto getWeeklyStats() {
        // TODO: integrate pointageEnAttente and tauxAffectation with timesheet module
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
        long conges = leaveRequestRepository.countByEtatDemandeAndYearMonth(
                DemandeEtat.VALIDE, now.getYear(), now.getMonthValue());

        return new RecruitmentStatsDto(recrutements, conges);
    }

    public List<RecentActivityDto> getRecentActivity(int size) {
        // TODO: replace with unified audit log when activity tracking is added
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
