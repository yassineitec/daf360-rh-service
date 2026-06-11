package com.daf360.rh.service;

import com.daf360.rh.domain.BreakLegalRule;
import com.daf360.rh.domain.BreakTemplate;
import com.daf360.rh.dto.break_.ComputedBreakDeduction;
import com.daf360.rh.repository.BreakLegalRuleRepository;
import com.daf360.rh.repository.BreakTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BreakDeductionService {

    private final BreakTemplateRepository templateRepo;
    private final BreakLegalRuleRepository legalRuleRepo;
    private final BreakCalculationEngine engine;
    private final JdbcTemplate jdbc;

    /**
     * Computes automatic break deductions for an employee's work record.
     * paysId can be passed directly when known; if null, it is resolved from the profile.
     */
    @Transactional(readOnly = true)
    public List<ComputedBreakDeduction> computeDeductions(
            Long regimeId,
            BigDecimal grossHours,
            LocalDate workDate,
            LocalTime workStartTime,
            LocalTime workEndTime,
            Long paysId) {

        if (paysId == null) {
            log.warn("computeDeductions called with null paysId — no deductions applied");
            return List.of();
        }

        // Load entity weekend days (pays_weekends.day is VARCHAR like 'FRIDAY')
        Set<DayOfWeek> entityWeekendDays = loadEntityWeekendDays(paysId);

        // Load break templates for this regime
        List<BreakTemplate> templates = regimeId != null
                ? templateRepo.findByRegimeIdAndIsActiveTrueOrderBySortOrderAsc(regimeId)
                : List.of();

        // Load applicable legal rules for this entity + date + hours
        List<BreakLegalRule> legalRules = legalRuleRepo.findApplicableRules(
                paysId, grossHours, workDate);

        return engine.computeAutoDeductions(
                workStartTime, workEndTime,
                grossHours, templates, legalRules,
                workDate, entityWeekendDays);
    }

    /** Total net hours after deducting all computed breaks. */
    public BigDecimal computeNetHours(
            Long regimeId,
            BigDecimal grossHours,
            LocalDate workDate,
            LocalTime workStartTime,
            LocalTime workEndTime,
            Long paysId) {

        List<ComputedBreakDeduction> deductions = computeDeductions(
                regimeId, grossHours, workDate, workStartTime, workEndTime, paysId);

        BigDecimal totalDeducted = deductions.stream()
                .map(ComputedBreakDeduction::getDeductedHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal net = grossHours.subtract(totalDeducted);
        return net.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : net.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Resolves paysId from employee_profiles table.
     * Single-query helper to avoid loading the full entity.
     */
    public Long resolvePaysIdForProfile(Long employeeProfileId) {
        try {
            return jdbc.queryForObject(
                    "SELECT pays_id FROM [dbo].[employee_profiles] WHERE id = ? AND deleted = 0",
                    Long.class, employeeProfileId);
        } catch (Exception e) {
            log.warn("Could not resolve paysId for employeeProfileId={}: {}", employeeProfileId, e.getMessage());
            return null;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Set<DayOfWeek> loadEntityWeekendDays(Long paysId) {
        Set<DayOfWeek> weekends = new HashSet<>();
        try {
            List<String> days = jdbc.queryForList(
                    "SELECT [day] FROM [dbo].[pays_weekends] WHERE pays_id = ?",
                    String.class, paysId);
            for (String day : days) {
                DayOfWeek dow = BreakCalculationEngine.parseDayVarchar(day);
                if (dow != null) weekends.add(dow);
            }
        } catch (Exception e) {
            log.warn("Could not load weekend days for paysId={}, defaulting to SAT+SUN: {}", paysId, e.getMessage());
            weekends.add(DayOfWeek.SATURDAY);
            weekends.add(DayOfWeek.SUNDAY);
        }
        if (weekends.isEmpty()) {
            weekends.add(DayOfWeek.SATURDAY);
            weekends.add(DayOfWeek.SUNDAY);
        }
        return weekends;
    }
}
