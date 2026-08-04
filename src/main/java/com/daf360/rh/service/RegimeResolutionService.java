package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.BreakTemplateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeResolutionService {

    private final EmployeeProfileRepository profileRepo;
    private final RegimeRoleAssignmentRepository roleAssignRepo;
    private final WorkingTimeRegimeRepository regimeRepo;
    private final BreakTemplateRepository breakTemplateRepo;
    private final PaysWeekendService weekendService;
    private final JdbcTemplate jdbc;

    /**
     * Resolves the regime for a PORTAL USER id.
     *
     * employee_profiles.id and Users.id are different sequences (profile 1 → user 223),
     * so callers holding a userId — the whole pointage module does — must come through
     * here. Passing a userId to {@link #resolveForEmployee} silently returns a different
     * employee's schedule.
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForUser(Long userId) {
        EmployeeProfile profile = profileRepo.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "No employee profile for userId: " + userId));
        return resolve(profile, LocalDate.now());
    }

    /**
     * Resolves which work regime applies to an employee PROFILE.
     * @param employeeProfileId employee_profiles.id — NOT Users.id (see {@link #resolveForUser}).
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForEmployee(Long employeeProfileId) {
        EmployeeProfile profile = profileRepo.findById(employeeProfileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee profile not found: " + employeeProfileId));
        return resolve(profile, LocalDate.now());
    }

    /**
     * Resolution rules, first match wins. Returns null when nothing is configured —
     * callers MUST surface "no schedule configured" rather than substituting defaults.
     *
     * 1. SEASONAL — an active seasonal window on the entity's regimes (temporary schedule,
     *    e.g. summer séance unique). Outranks everything, per HR requirement.
     * 2. EMPLOYEE_OVERRIDE — employee_profiles.regime_template_id, now honouring
     *    regime_start_date / regime_end_date and the existing cross-pays guard.
     * 3. ROLE_ASSIGNMENT — active regime_role_assignments for the user's role, ordered.
     * 4. DEFAULT — the entity's is_default regime.
     */
    private ResolvedRegimeDto resolve(EmployeeProfile profile, LocalDate date) {
        Long profileId = profile.getId();
        Long paysId    = profile.getPaysId();

        // PRIORITY 1: Seasonal / temporary regime for the entity.
        List<WorkingTimeRegime> seasonal = regimeRepo.findActiveSeasonalForPays(paysId, date);
        if (!seasonal.isEmpty()) {
            if (seasonal.size() > 1) {
                log.warn("{} overlapping seasonal regimes for paysId={} on {} — using regime {}. "
                       + "Seasonal windows should not overlap.",
                        seasonal.size(), paysId, date, seasonal.get(0).getId());
            }
            WorkingTimeRegime r = seasonal.get(0);
            return mapToDto(r, "SEASONAL", r.getSeasonalFrom(), r.getSeasonalTo());
        }

        // PRIORITY 2: Personal override.
        if (profile.getRegimeTemplateId() != null) {
            Optional<WorkingTimeRegime> regime = regimeRepo.findById(profile.getRegimeTemplateId());
            if (regime.isPresent() && Boolean.TRUE.equals(regime.get().getIsActive())) {
                if (!regime.get().getPaysId().equals(paysId)) {
                    // pays guard: never return a regime from a different entity
                    log.warn("Employee {} has regime {} from different pays (regime.pays={}, profile.pays={}). Ignoring override.",
                            profileId, regime.get().getId(), regime.get().getPaysId(), paysId);
                } else if (!withinWindow(date, profile.getRegimeStartDate(), profile.getRegimeEndDate())) {
                    // The columns existed but were never enforced — an expired personal
                    // override used to apply forever.
                    log.debug("Employee {} personal override is outside its window ({} → {}), falling through",
                            profileId, profile.getRegimeStartDate(), profile.getRegimeEndDate());
                } else {
                    return mapToDto(regime.get(), "EMPLOYEE_OVERRIDE",
                            profile.getRegimeStartDate(), profile.getRegimeEndDate());
                }
            } else {
                log.debug("Employee {} has regime_template_id={} but regime is inactive or missing, falling through",
                        profileId, profile.getRegimeTemplateId());
            }
        }

        // PRIORITY 3: Role-based assignment.
        Long roleId = queryUserRoleId(profile.getUserId());
        if (roleId != null) {
            List<RegimeRoleAssignment> assignments =
                    roleAssignRepo.findAllActiveForRoleAndPays(roleId, paysId, date);
            if (!assignments.isEmpty()) {
                if (assignments.size() > 1) {
                    log.warn("{} overlapping regime assignments for roleId={} paysId={} on {} — using assignment {}.",
                            assignments.size(), roleId, paysId, date, assignments.get(0).getId());
                }
                RegimeRoleAssignment a = assignments.get(0);
                return mapToDto(a.getRegime(), "ROLE_ASSIGNMENT",
                        a.getEffectiveFrom(), a.getEffectiveTo());
            }
        }

        // PRIORITY 4: Pays default.
        Optional<WorkingTimeRegime> defaultRegime = regimeRepo
                .findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId);
        if (defaultRegime.isPresent()) {
            return mapToDto(defaultRegime.get(), "DEFAULT", null, null);
        }

        log.warn("No regime configured for employeeProfileId={} (paysId={})", profileId, paysId);
        return null;
    }

    /** Inclusive window test; null bounds are open-ended. */
    private static boolean withinWindow(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) return false;
        return to == null || !date.isAfter(to);
    }

    /**
     * Resolves which regime applies to a role+pays combination.
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForRole(Long roleId, Long paysId) {
        Optional<RegimeRoleAssignment> assignment = roleAssignRepo
                .findActiveForRoleAndPays(roleId, paysId, LocalDate.now());
        if (assignment.isPresent()) {
            return mapToDto(assignment.get().getRegime(), "ROLE_ASSIGNMENT",
                    assignment.get().getEffectiveFrom(), assignment.get().getEffectiveTo());
        }
        return regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId)
                .map(r -> mapToDto(r, "DEFAULT", null, null))
                .orElse(null);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Long queryUserRoleId(Long userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT role_id FROM [dbo].[Users] WHERE id = ?",
                    Long.class, userId);
        } catch (Exception e) {
            log.debug("Could not fetch role_id for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private static final DateTimeFormatter HHmm = DateTimeFormatter.ofPattern("HH:mm");

    private ResolvedRegimeDto mapToDto(WorkingTimeRegime r, String level,
                                       LocalDate from, LocalDate to) {
        ResolvedRegimeDto dto = new ResolvedRegimeDto();
        dto.setRegimeId(r.getId());
        dto.setRegimeCode(r.getCode());
        dto.setRegimeLabelFr(r.getLabelFr());
        dto.setRegimeLabelEn(r.getLabelEn());
        dto.setHoursPerWeek(r.getHoursPerWeek());
        dto.setDaysPerWeek(r.getDaysPerWeek());
        dto.setStartTime(r.getStartTime());
        dto.setEndTime(r.getEndTime());
        dto.setIsFlexible(r.getIsFlexible());
        dto.setBreakDurationMin(r.getBreakDurationMin());
        dto.setOvertimeAllowed(r.getOvertimeAllowed());
        dto.setMaxHoursPerDay(r.getMaxHoursPerDay());
        dto.setAssignmentLevel(level);
        dto.setEffectiveFrom(from);
        dto.setEffectiveTo(to);
        dto.setPaysId(r.getPaysId());
        dto.setIsSeasonal("SEASONAL".equals(level));

        // pointage-friendly aliases. NO fabricated fallbacks: a regime with no hours
        // returns null so the caller can say "not configured" instead of silently
        // presenting an invented 08:00–17:00 day.
        dto.setRegimeName(r.getLabelFr());
        dto.setHeureDebut(r.getStartTime() != null ? r.getStartTime().format(HHmm) : null);
        dto.setHeureFin(r.getEndTime()     != null ? r.getEndTime().format(HHmm)   : null);
        dto.setPauseDejeuner(r.getBreakDurationMin());
        // Working days come from the entity's configured weekend, not from days_per_week.
        dto.setJoursOuvrables(weekendService.workingDayLabels(r.getPaysId()));
        if (r.getHoursPerWeek() != null && r.getDaysPerWeek() != null && r.getDaysPerWeek() > 0) {
            dto.setHeuresJour(r.getHoursPerWeek().doubleValue() / r.getDaysPerWeek());
        } else {
            dto.setHeuresJour(null);
        }
        if (r.getStartTime() == null || r.getEndTime() == null) {
            log.warn("Regime {} ({}) has no start/end time — pointage automation cannot run for it.",
                    r.getId(), r.getCode());
        }

        // Active break windows for this regime (folded in so the pointage store gets
        // regime + breaks in a single call — the scheduler reads the same source and
        // needs no ADMIN_BREAKS permission).
        List<ResolvedRegimeDto.BreakWindow> breaks = new ArrayList<>();
        breakTemplateRepo.findByRegimeIdAndIsActiveTrueOrderBySortOrderAsc(r.getId())
                .forEach(b -> {
                    if (b.getBreakTimeStart() != null && b.getBreakTimeEnd() != null) {
                        breaks.add(new ResolvedRegimeDto.BreakWindow(
                                b.getBreakTimeStart().format(HHmm),
                                b.getBreakTimeEnd().format(HHmm),
                                b.getStatusCode(),
                                b.getLabelFr(),
                                b.getLabelEn(),
                                b.getDurationMin(),
                                b.getAppliesToDays()));
                    }
                });
        dto.setBreaks(breaks);

        return dto;
    }

    /**
     * Current week's expected schedule for a resolved regime. Lives here (not in the
     * controller) because working days depend on the entity's configured weekend.
     */
    public com.daf360.rh.dto.regime.WeeklyScheduleDto buildWeeklySchedule(ResolvedRegimeDto regime,
                                                                          LocalDate anyDayOfWeek) {
        LocalDate weekStart = anyDayOfWeek.with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

        java.util.Set<java.time.DayOfWeek> workingDays = weekendService.workingDays(regime.getPaysId());
        double hoursPerDay = regime.getHeuresJour() != null ? regime.getHeuresJour() : 0.0;

        List<com.daf360.rh.dto.regime.WeeklyScheduleDto.DaySchedule> days = new ArrayList<>();
        double totalHours = 0.0;
        for (int i = 0; i < 7; i++) {
            LocalDate date  = weekStart.plusDays(i);
            boolean isWork  = workingDays.contains(date.getDayOfWeek());
            double expected = isWork ? hoursPerDay : 0.0;
            totalHours     += expected;
            days.add(new com.daf360.rh.dto.regime.WeeklyScheduleDto.DaySchedule(
                    date, PaysWeekendService.frenchLabel(date.getDayOfWeek()), isWork, expected));
        }
        return new com.daf360.rh.dto.regime.WeeklyScheduleDto(
                weekStart, weekStart.plusDays(6), totalHours, days);
    }

}
