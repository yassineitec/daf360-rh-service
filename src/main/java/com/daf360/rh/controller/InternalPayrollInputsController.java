package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.RegimeResolutionService;
import com.daf360.rh.service.WorkingDayCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything RH knows that a payslip needs, for ONE employee and ONE pay period — read by
 * daf360-payroll-service's run ({@code PayrollVariableInputsService}) and by the « Calculer la
 * paie » pop-up's preview. Whether an absence costs salary is NOT decided here: payroll owns
 * that rule per country (payroll_leave_deduction_rules) and reads the catalogue below.
 *
 * <h3>Pay period</h3>
 * Payroll sends {@code from} / {@code to}: its per-country period (e.g. November = 21/10 →
 * 20/11). Without them the calendar month {@code year}/{@code month} is used. Every figure below
 * is about that window.
 *
 * <ul>
 *   <li><b>Country of work</b> — {@code employee_profiles.pays_id}, never the nationality: its
 *       weekend ({@code pays_weekends}) and public holidays decide which timesheet hours are
 *       weekend overtime and how many working days the period has.</li>
 *   <li><b>Régime</b> resolved on the last day of the period — hours per week, turned into an
 *       hourly rate by payroll.</li>
 *   <li><b>Entry / exit</b> — hire date and the agreed last working day of a non-cancelled
 *       offboarding: working days of the period outside them are not paid.</li>
 *   <li><b>Approved absences</b> overlapping the period, clipped to it.</li>
 *   <li><b>Approved autorisations</b> of the period in minutes, with the country's monthly quota
 *       ({@code pays.autorisation_monthly_minutes}).</li>
 *   <li><b>Balances</b> (congé, maladie) at the start and at the end of the period. RH stores only
 *       the CURRENT balance ({@code Users.solde*}, debited when a leave is approved) and no
 *       history, so they are rebuilt from the approved leaves that draw on each balance:
 *       end = current + days approved and dated after the period; start = end + days inside
 *       the period. A manual correction of a balance by HR carries no date and cannot be placed —
 *       it is counted as if it had always been there.</li>
 * </ul>
 *
 * Service-to-service only ({@code X-Internal-Key}, fails closed when the key is unset), under
 * SecurityConfig's existing GET {@code /api/hr/internal/**} rule. 204 = no HR profile.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalPayrollInputsController {

    private static final String HEADER = "X-Internal-Key";
    /** Balance codes of AbsenceTypes.balance_field → the Users column that holds the current figure. */
    private static final Map<String, String> BALANCE_COLUMNS = new LinkedHashMap<>();
    static {
        BALANCE_COLUMNS.put("CONGE", "soldeConge");
        BALANCE_COLUMNS.put("MALADIE", "soldeMaladie");
    }

    private final EmployeeProfileRepository profileRepo;
    private final RegimeResolutionService regimeResolution;
    private final WorkingDayCalculator calendar;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    /** GET /api/hr/internal/payroll-inputs/employees/{userId}?year=2026&month=11[&from=2026-10-21&to=2026-11-20] */
    @GetMapping("/api/hr/internal/payroll-inputs/employees/{userId}")
    @Transactional(readOnly = true)
    public ResponseEntity<PayrollInputs> inputs(
            @PathVariable Long userId,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        if (month < 1 || month > 12 || year < 2000 || year > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year/month out of range");
        }
        if ((from == null) != (to == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to go together");
        }
        if (from == null) {
            YearMonth ym = YearMonth.of(year, month);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 62) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be 1 to 63 days");
        }

        EmployeeProfile profile = profileRepo.findByUserId(userId)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .orElse(null);
        if (profile == null) return ResponseEntity.noContent().build();

        Long paysId = profile.getPaysId();
        List<String> weekend = calendar.weekendFor(paysId).stream()
                .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .map(DayOfWeek::name)
                .toList();
        List<LocalDate> holidays = calendar.holidaysBetween(paysId, from, to).stream().sorted().toList();
        long workingDaysInPeriod = calendar.countWorkingDays(from, to, paysId);

        LocalDate hireDate = profile.getHireDate();
        LocalDate exitDate = exitDate(profile.getId());
        LocalDate employedFrom = hireDate != null && hireDate.isAfter(from) ? hireDate : from;
        LocalDate employedTo = exitDate != null && exitDate.isBefore(to) ? exitDate : to;
        long workingDaysEmployed = employedTo.isBefore(employedFrom) ? 0
                : calendar.countWorkingDays(employedFrom, employedTo, paysId);

        BigDecimal hoursPerWeek = null;
        Integer daysPerWeek = null;
        String regimeCode = null;
        try {
            ResolvedRegimeDto regime = regimeResolution.resolveForUser(userId, to);
            if (regime != null) {
                hoursPerWeek = regime.getHoursPerWeek();
                daysPerWeek = regime.getDaysPerWeek();
                regimeCode = regime.getRegimeCode();
            }
        } catch (RuntimeException e) {
            // No régime is a gap payroll reports on the payslip, not a reason to refuse the run.
            log.warn("payroll-inputs: régime not resolvable for user {} on {}: {}", userId, to, e.getMessage());
        }

        RoleRef role = roleOf(userId);
        return ResponseEntity.ok(new PayrollInputs(
                userId, paysId, year, month, from, to,
                weekend, holidays, workingDaysInPeriod,
                hireDate, exitDate, workingDaysEmployed,
                regimeCode, hoursPerWeek, daysPerWeek,
                absences(userId, paysId, from, to),
                autorisationMinutes(userId, from, to),
                monthlyAutorisationQuota(paysId),
                balances(userId, paysId, from, to),
                role != null ? role.id() : null,
                role != null ? role.labelFr() : null));
    }

    /**
     * Reference data for payroll's settings screens: the weekend days of a country (the days a
     * weekend-overtime majoration can be set for), the RH roles (a daily overtime cap can be set
     * per role) and, with {@code userId}, that employee's country of work and role.
     *
     * GET /api/hr/internal/payroll-settings-ref?paysId=178[&userId=207]
     */
    @GetMapping("/api/hr/internal/payroll-settings-ref")
    @Transactional(readOnly = true)
    public SettingsRef settingsRef(@RequestParam(required = false) Long paysId,
                                   @RequestParam(required = false) Long userId,
                                   @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        Long employeePays = null;
        RoleRef employeeRole = null;
        if (userId != null) {
            employeePays = profileRepo.findByUserId(userId)
                    .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                    .map(EmployeeProfile::getPaysId).orElse(null);
            employeeRole = roleOf(userId);
        }
        Long pays = paysId != null ? paysId : employeePays;
        List<String> weekend = pays == null ? List.of() : calendar.weekendFor(pays).stream()
                .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .map(DayOfWeek::name)
                .toList();
        List<RoleRef> roles = jdbcTemplate.query("""
                SELECT id, frenchName, englishName FROM [dbo].[Roles]
                WHERE ISNULL(deleted, 0) = 0 ORDER BY frenchName
                """, (rs, i) -> new RoleRef(rs.getLong("id"), rs.getString("frenchName"), rs.getString("englishName")));
        return new SettingsRef(pays, weekend, roles, userId, employeePays,
                employeeRole != null ? employeeRole.id() : null);
    }

    private RoleRef roleOf(Long userId) {
        List<RoleRef> rows = jdbcTemplate.query("""
                SELECT r.id, r.frenchName, r.englishName
                FROM   [dbo].[Users] u JOIN [dbo].[Roles] r ON r.id = u.role_id
                WHERE  u.id = ?
                """, (rs, i) -> new RoleRef(rs.getLong("id"), rs.getString("frenchName"), rs.getString("englishName")), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The leave catalogue (AbsenceTypes, global to every country) for payroll's admin screen
     * « Absences et période de paie », where each country decides which types cost salary.
     * Retired (soft-deleted) types are left out; inactive ones are returned with active=false so
     * a rule saved while they were active still shows.
     */
    @GetMapping("/api/hr/internal/absence-types")
    @Transactional(readOnly = true)
    public List<AbsenceTypeItem> absenceTypes(@RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        return jdbcTemplate.query("""
                SELECT code, label_fr, label_en, [isActive] AS active, tracks_balance, balance_field, display_order
                FROM   [dbo].[AbsenceTypes]
                WHERE  deleted = 0
                ORDER BY display_order, code
                """, (rs, i) -> new AbsenceTypeItem(
                        rs.getString("code"), rs.getString("label_fr"), rs.getString("label_en"),
                        rs.getBoolean("active"), rs.getBoolean("tracks_balance"),
                        rs.getString("balance_field"), rs.getInt("display_order")));
    }

    /** Approved absences overlapping the period, clipped to it. */
    private List<AbsenceLine> absences(Long userId, Long paysId, LocalDate from, LocalDate to) {
        List<AbsenceLine> out = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT a.type, a.category, a.date_debut, a.date_fin, a.pays_id,
                       t.label_fr, t.label_en, t.balance_field, t.tracks_balance
                FROM   [dbo].[absences] a
                LEFT JOIN [dbo].[AbsenceTypes] t ON t.code = a.type
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.collaborateur_id = ?
                  AND  a.date_debut <= ? AND a.date_fin >= ?
                ORDER BY a.date_debut
                """, rs -> {
            LocalDate start = rs.getDate("date_debut").toLocalDate();
            LocalDate end = rs.getDate("date_fin").toLocalDate();
            LocalDate s = start.isBefore(from) ? from : start;
            LocalDate e = end.isAfter(to) ? to : end;
            Number rowPays = (Number) rs.getObject("pays_id");
            BigDecimal days = calendar.billableDays(s, e, LeaveCategory.valueOf(rs.getString("category")),
                    rowPays != null ? rowPays.longValue() : paysId);
            String code = rs.getString("type");
            out.add(new AbsenceLine(
                    code,
                    rs.getString("label_fr") != null ? rs.getString("label_fr") : code,
                    rs.getString("label_en") != null ? rs.getString("label_en") : code,
                    rs.getString("category"),
                    s, e, days,
                    rs.getBoolean("tracks_balance") ? rs.getString("balance_field") : null));
        }, userId, Date.valueOf(to), Date.valueOf(from));
        return out;
    }

    /**
     * Congé and maladie balances at the start and at the end of the period (see the class
     * comment for how they are rebuilt). Only approved leaves of types that draw on the balance
     * count; days are counted the way the debit counted them (working days of the leave's
     * country, half-days as 0.5).
     */
    private List<BalanceLine> balances(Long userId, Long paysId, LocalDate from, LocalDate to) {
        Map<String, Double> current = jdbcTemplate.query(
                "SELECT soldeConge, soldeMaladie FROM [dbo].[Users] WHERE id = ?",
                rs -> {
                    Map<String, Double> m = new LinkedHashMap<>();
                    if (rs.next()) {
                        Number c = (Number) rs.getObject("soldeConge");
                        Number mal = (Number) rs.getObject("soldeMaladie");
                        m.put("CONGE", c != null ? c.doubleValue() : null);
                        m.put("MALADIE", mal != null ? mal.doubleValue() : null);
                    }
                    return m;
                }, userId);

        Map<String, BigDecimal> inPeriod = new LinkedHashMap<>();
        Map<String, BigDecimal> afterPeriod = new LinkedHashMap<>();
        BALANCE_COLUMNS.keySet().forEach(k -> { inPeriod.put(k, BigDecimal.ZERO); afterPeriod.put(k, BigDecimal.ZERO); });

        jdbcTemplate.query("""
                SELECT a.category, a.date_debut, a.date_fin, a.pays_id, UPPER(t.balance_field) AS balance_field
                FROM   [dbo].[absences] a
                JOIN   [dbo].[AbsenceTypes] t ON t.code = a.type
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.collaborateur_id = ?
                  AND  t.tracks_balance = 1
                  AND  UPPER(t.balance_field) IN ('CONGE', 'MALADIE')
                  AND  a.date_fin >= ?
                """, rs -> {
            String field = rs.getString("balance_field");
            LocalDate start = rs.getDate("date_debut").toLocalDate();
            LocalDate end = rs.getDate("date_fin").toLocalDate();
            LeaveCategory category = LeaveCategory.valueOf(rs.getString("category"));
            Number rowPays = (Number) rs.getObject("pays_id");
            Long p = rowPays != null ? rowPays.longValue() : paysId;
            // Part inside the period.
            LocalDate s = start.isBefore(from) ? from : start;
            LocalDate e = end.isAfter(to) ? to : end;
            if (!e.isBefore(s)) inPeriod.merge(field, calendar.billableDays(s, e, category, p), BigDecimal::add);
            // Part after the period (already debited from the current balance).
            if (end.isAfter(to)) {
                LocalDate s2 = start.isAfter(to) ? start : to.plusDays(1);
                afterPeriod.merge(field, calendar.billableDays(s2, end, category, p), BigDecimal::add);
            }
        }, userId, Date.valueOf(from));

        List<BalanceLine> out = new ArrayList<>();
        for (String field : BALANCE_COLUMNS.keySet()) {
            Double cur = current.get(field);
            if (cur == null) {
                out.add(new BalanceLine(field, null, null, inPeriod.get(field), null));
                continue;
            }
            BigDecimal now = BigDecimal.valueOf(cur);
            BigDecimal atEnd = now.add(afterPeriod.get(field));
            BigDecimal atStart = atEnd.add(inPeriod.get(field));
            out.add(new BalanceLine(field, atStart, atEnd, inPeriod.get(field), now));
        }
        return out;
    }

    private int autorisationMinutes(Long userId, LocalDate from, LocalDate to) {
        Integer minutes = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(a.duree_minutes), 0)
                FROM   [dbo].[autorisations] a
                WHERE  a.etat_demande = 'VALIDE'
                  AND  a.collaborateur_id = ?
                  AND  a.date_autorisation BETWEEN ? AND ?
                """, Integer.class, userId, Date.valueOf(from), Date.valueOf(to));
        return minutes == null ? 0 : minutes;
    }

    /** NULL = no monthly limit for the country, i.e. every approved minute is paid. */
    private Integer monthlyAutorisationQuota(Long paysId) {
        if (paysId == null) return null;
        List<Integer> rows = jdbcTemplate.query(
                "SELECT autorisation_monthly_minutes FROM [dbo].[pays] WHERE id = ?",
                (rs, i) -> (Integer) rs.getObject(1, Integer.class), paysId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Agreed last working day of the latest non-cancelled offboarding, if any. */
    private LocalDate exitDate(Long profileId) {
        List<LocalDate> rows = jdbcTemplate.query("""
                SELECT TOP 1 last_working_day
                FROM   [dbo].[offboarding_workflow_instances]
                WHERE  employee_profile_id = ?
                  AND  status <> 'CANCELLED'
                  AND  last_working_day IS NOT NULL
                ORDER BY last_working_day DESC
                """, (rs, i) -> rs.getDate(1).toLocalDate(), profileId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        if (provided == null || !java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }

    /** Mirrored by payroll-service's {@code HrPayrollInputsClient.HrPayrollInputs}. */
    public record PayrollInputs(
            Long userId,
            /** employee_profiles.pays_id — the country of WORK. */
            Long paysId,
            int year,
            int month,
            /** The pay period the figures are about (calendar month when payroll sent none). */
            LocalDate periodFrom,
            LocalDate periodTo,
            /** DayOfWeek names, e.g. ["SATURDAY","SUNDAY"]. */
            List<String> weekendDays,
            List<LocalDate> holidays,
            long workingDaysInPeriod,
            LocalDate hireDate,
            LocalDate exitDate,
            /** Working days of the period between hire and exit (the whole period when neither falls in it). */
            long workingDaysEmployed,
            String regimeCode,
            BigDecimal hoursPerWeek,
            Integer daysPerWeek,
            List<AbsenceLine> absences,
            int autorisationMinutes,
            /** Null = no monthly limit. */
            Integer autorisationMonthlyQuotaMinutes,
            List<BalanceLine> balances,
            /** RH role (Users.role_id) — payroll can set a daily overtime cap per role. */
            Long roleId,
            String roleLabel
    ) {}

    public record RoleRef(long id, String labelFr, String labelEn) {}

    public record SettingsRef(
            Long paysId,
            /** DayOfWeek names of the country's weekend. */
            List<String> weekendDays,
            List<RoleRef> roles,
            Long userId,
            /** The employee's country of work (with userId). */
            Long employeePaysId,
            Long employeeRoleId
    ) {}

    public record AbsenceLine(
            String typeCode,
            String labelFr,
            String labelEn,
            String category,
            LocalDate from,
            LocalDate to,
            /** Working days of the clipped range (half-day = 0.5). */
            BigDecimal days,
            /** CONGE / MALADIE when the type draws on a balance, else null. */
            String balanceField
    ) {}

    /** One balance (CONGE / MALADIE). Null figures = the employee has no balance recorded. */
    public record BalanceLine(
            String field,
            BigDecimal atPeriodStart,
            BigDecimal atPeriodEnd,
            /** Days of the period drawn on this balance. */
            BigDecimal takenInPeriod,
            BigDecimal current
    ) {}

    public record AbsenceTypeItem(
            String code,
            String labelFr,
            String labelEn,
            boolean active,
            boolean tracksBalance,
            String balanceField,
            int displayOrder
    ) {}
}
