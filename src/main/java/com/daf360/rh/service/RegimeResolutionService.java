package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.BreakTemplateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeResolutionService {

    public static final String LEVEL_SEASONAL = "SEASONAL";
    public static final String LEVEL_EMPLOYEE = "EMPLOYEE_OVERRIDE";
    public static final String LEVEL_ROLE     = "ROLE_ASSIGNMENT";
    public static final String LEVEL_DEFAULT  = "DEFAULT";

    private final EmployeeProfileRepository profileRepo;
    private final RegimeRoleAssignmentRepository roleAssignRepo;
    private final EmployeeRegimeAssignmentRepository personalRepo;
    private final WorkingTimeRegimeRepository regimeRepo;
    private final BreakTemplateRepository breakTemplateRepo;
    private final PaysWeekendService weekendService;
    private final PaysTimezoneService timezoneService;
    private final JdbcTemplate jdbc;

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Resolves the regime for a PORTAL USER id, today.
     *
     * employee_profiles.id and Users.id are different sequences (profile 1 → user 223),
     * so callers holding a userId — the whole pointage module does — must come through
     * here. Passing a userId to {@link #resolveForEmployee} silently returns a different
     * employee's schedule.
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForUser(Long userId) {
        return resolveForUser(userId, null);
    }

    /** Same, for a given date of the employee's calendar (null = today in the entity's zone). */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForUser(Long userId, LocalDate date) {
        EmployeeProfile profile = profileRepo.findByUserId(userId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "No employee profile for userId: " + userId));
        return resolve(profile, date != null ? date : todayIn(profile.getPaysId()));
    }

    /**
     * Resolves which work regime applies to an employee PROFILE, today.
     * @param employeeProfileId employee_profiles.id — NOT Users.id (see {@link #resolveForUser}).
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForEmployee(Long employeeProfileId) {
        return resolveForEmployee(employeeProfileId, null);
    }

    /** Same, for a given date (null = today in the entity's zone). */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForEmployee(Long employeeProfileId, LocalDate date) {
        EmployeeProfile profile = profileRepo.findById(employeeProfileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee profile not found: " + employeeProfileId));
        return resolve(profile, date != null ? date : todayIn(profile.getPaysId()));
    }

    /**
     * What the employee would get WITHOUT a seasonal window and WITHOUT a personal
     * assignment: their role's assignment, else the entity default. The onboarding wizard
     * uses it to decide whether the regime RH picked is a real personal choice or just the
     * inherited one (in which case nothing personal is stored — see
     * EmployeeRegimeAssignmentService#recordOnboardingChoice).
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveInherited(Long userId, Long paysId, LocalDate date) {
        ResolvedRegimeDto role = resolveRoleLevel(queryUserRoleId(userId), paysId, date);
        if (role != null) return role;
        return regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId)
                .map(r -> stamp(mapToDto(r, LEVEL_DEFAULT, null, null), date))
                .orElse(null);
    }

    /** "Today" for an entity, in ITS zone (server date when the entity has none). */
    public LocalDate todayIn(Long paysId) {
        ZoneId zone = timezoneService.zoneFor(paysId);
        return zone != null ? LocalDate.now(zone) : LocalDate.now();
    }

    /**
     * Resolution rules, first match wins. Returns null when nothing is configured —
     * callers MUST surface "no schedule configured" rather than substituting defaults.
     *
     * 1. SEASONAL — an active seasonal window on the entity's regimes covering the date
     *    (e.g. summer séance unique). Outranks everything, per HR requirement.
     * 2. EMPLOYEE_OVERRIDE — the newest personal assignment (employee_regime_assignments,
     *    V127) covering the date, whose regime is active, of the same entity and not
     *    seasonal (a seasonal regime only ever applies through its own window).
     * 3. ROLE_ASSIGNMENT — the newest role assignment covering the date.
     * 4. DEFAULT — the entity's is_default regime.
     *
     * Historical answers are as good as the data allows: the role and the entity are the
     * employee's CURRENT ones, and a seasonal window is a single date range that RH moves
     * every year, so a past summer is no longer known once next summer's dates are set.
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
            return stamp(mapToDto(r, LEVEL_SEASONAL, r.getSeasonalFrom(), r.getSeasonalTo()), date);
        }

        // PRIORITY 2: Personal assignment covering the date.
        for (EmployeeRegimeAssignment a : personalRepo.findCovering(profileId, date)) {
            WorkingTimeRegime r = a.getRegime();
            if (usableAsAssignment(r, paysId)) {
                return stamp(mapToDto(r, LEVEL_EMPLOYEE, a.getEffectiveFrom(), a.getEffectiveTo()), date);
            }
            log.warn("Employee {} personal assignment {} points to regime {} which is inactive, seasonal "
                   + "or of another entity — ignored.", profileId, a.getId(), r.getId());
        }

        // PRIORITY 3 + 4: role, then default.
        ResolvedRegimeDto inherited = resolveInherited(profile.getUserId(), paysId, date);
        if (inherited == null) {
            log.warn("No regime configured for employeeProfileId={} (paysId={}) on {}", profileId, paysId, date);
        }
        return inherited;
    }

    private ResolvedRegimeDto resolveRoleLevel(Long roleId, Long paysId, LocalDate date) {
        if (roleId == null) return null;
        List<RegimeRoleAssignment> assignments = roleAssignRepo.findAllActiveForRoleAndPays(roleId, paysId, date);
        for (RegimeRoleAssignment a : assignments) {
            if (usableAsAssignment(a.getRegime(), paysId)) {
                return stamp(mapToDto(a.getRegime(), LEVEL_ROLE, a.getEffectiveFrom(), a.getEffectiveTo()), date);
            }
        }
        return null;
    }

    /** A regime an assignment may point to: active, same entity, not a seasonal one. */
    static boolean usableAsAssignment(WorkingTimeRegime r, Long paysId) {
        return r != null
                && Boolean.TRUE.equals(r.getIsActive())
                && r.getPaysId() != null && r.getPaysId().equals(paysId)
                && r.getSeasonalFrom() == null;
    }

    /**
     * Resolves which regime applies to a role+pays combination, today.
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForRole(Long roleId, Long paysId) {
        LocalDate today = todayIn(paysId);
        ResolvedRegimeDto role = resolveRoleLevel(roleId, paysId, today);
        if (role != null) return role;
        return regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId)
                .map(r -> stamp(mapToDto(r, LEVEL_DEFAULT, null, null), today))
                .orElse(null);
    }

    // ── Batch (overview screen) ───────────────────────────────────────────────

    /** Light result of {@link #resolveLevelsForPays}. */
    public record ResolvedLevel(Long regimeId, String regimeLabelFr, String level,
                                Long personalRegimeId) {}

    /**
     * Same rules as {@link #resolve}, for every given profile of ONE entity, with a fixed
     * number of queries — the overview used to run a full resolution (5+ queries) per
     * employee, one after the other.
     *
     * @param roleByUserId Users.role_id per user id (null role allowed)
     * @return per employee_profiles.id; a missing key means "no regime configured"
     */
    @Transactional(readOnly = true)
    public Map<Long, ResolvedLevel> resolveLevelsForPays(Long paysId, List<EmployeeProfile> profiles,
                                                         Map<Long, Long> roleByUserId) {
        LocalDate date = todayIn(paysId);
        Map<Long, ResolvedLevel> out = new HashMap<>();

        List<WorkingTimeRegime> seasonal = regimeRepo.findActiveSeasonalForPays(paysId, date);
        WorkingTimeRegime seasonalNow = seasonal.isEmpty() ? null : seasonal.get(0);

        // First (newest) usable personal row per profile.
        Map<Long, WorkingTimeRegime> personal = new HashMap<>();
        for (EmployeeRegimeAssignment a : personalRepo.findCoveringForPays(paysId, date)) {
            if (!personal.containsKey(a.getEmployeeProfileId()) && usableAsAssignment(a.getRegime(), paysId)) {
                personal.put(a.getEmployeeProfileId(), a.getRegime());
            }
        }
        // First (newest) usable role row per role.
        Map<Long, WorkingTimeRegime> byRole = new HashMap<>();
        for (RegimeRoleAssignment a : roleAssignRepo.findCoveringForPays(paysId, date)) {
            Long roleId = a.getRole().getId();
            if (!byRole.containsKey(roleId) && usableAsAssignment(a.getRegime(), paysId)) {
                byRole.put(roleId, a.getRegime());
            }
        }
        WorkingTimeRegime def = regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId).orElse(null);

        for (EmployeeProfile p : profiles) {
            WorkingTimeRegime own = personal.get(p.getId());
            Long ownId = own != null ? own.getId() : null;
            WorkingTimeRegime r;
            String level;
            if (seasonalNow != null) {
                r = seasonalNow; level = LEVEL_SEASONAL;
            } else if (own != null) {
                r = own; level = LEVEL_EMPLOYEE;
            } else {
                Long roleId = roleByUserId.get(p.getUserId());
                WorkingTimeRegime roleRegime = roleId != null ? byRole.get(roleId) : null;
                if (roleRegime != null) { r = roleRegime; level = LEVEL_ROLE; }
                else if (def != null)   { r = def;        level = LEVEL_DEFAULT; }
                else continue;
            }
            out.put(p.getId(), new ResolvedLevel(r.getId(), r.getLabelFr(), level, ownId));
        }
        return out;
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

    private static ResolvedRegimeDto stamp(ResolvedRegimeDto dto, LocalDate date) {
        dto.setResolvedForDate(date);
        return dto;
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
        dto.setIsSeasonal(LEVEL_SEASONAL.equals(level));

        // The clock this regime's hours are read in: its own override (a travel/temporary
        // regime) or the entity's zone. Left null when neither is configured, so consumers
        // report "not configured" instead of silently using the server's zone — the hour
        // fields below are meaningless without it.
        String zone = r.getTimezone() != null && !r.getTimezone().isBlank()
                ? r.getTimezone().trim()
                : timezoneService.timezoneFor(r.getPaysId());
        dto.setTimezone(zone);
        if (zone == null) {
            log.warn("Regime {} ({}) has no timezone and entity {} has none either — pointage "
                   + "automation cannot run for its employees.", r.getId(), r.getCode(), r.getPaysId());
        }

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
     * The expected schedule of the week containing {@code anyDayOfWeek}. Lives here (not in
     * the controller) because working days depend on the entity's configured weekend.
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
                weekStart, weekStart.plusDays(6), totalHours, days, regime.getTimezone());
    }
}
