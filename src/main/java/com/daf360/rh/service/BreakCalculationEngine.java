package com.daf360.rh.service;

import com.daf360.rh.domain.BreakLegalRule;
import com.daf360.rh.domain.BreakTemplate;
import com.daf360.rh.dto.break_.ComputedBreakDeduction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class BreakCalculationEngine {

    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    /**
     * Compute automatic break deductions for a work record.
     * Uses entity-specific weekend days (from pays_weekends.day varchar column).
     *
     * @param workStartTime work start time (nullable)
     * @param workEndTime work end time (nullable)
     * @param grossHours total gross hours worked
     * @param templates active break templates for the regime
     * @param legalRules applicable legal rules for the entity+date+hours
     * @param workDate the actual work date
     * @param entityWeekendDays DayOfWeek set for entity weekends (e.g. FRIDAY+SATURDAY for TN)
     */
    public List<ComputedBreakDeduction> computeAutoDeductions(
            LocalTime workStartTime,
            LocalTime workEndTime,
            BigDecimal grossHours,
            List<BreakTemplate> templates,
            List<BreakLegalRule> legalRules,
            LocalDate workDate,
            Set<DayOfWeek> entityWeekendDays) {

        List<ComputedBreakDeduction> result = new ArrayList<>();

        // Apply regime break templates
        for (BreakTemplate template : templates) {
            if (!Boolean.TRUE.equals(template.getIsActive())) continue;
            if ("OPTIONAL".equals(template.getDeductionType())) continue;
            if (!appliesToDay(template.getAppliesToDays(), workDate, entityWeekendDays)) continue;

            // OPTION C: If break has scheduled time range, compute overlap with work period
            if (template.getBreakTimeStart() != null && template.getBreakTimeEnd() != null
                    && workStartTime != null && workEndTime != null) {

                // Overlap = max(workStart, breakStart) .. min(workEnd, breakEnd)
                java.time.LocalTime overlapStart = workStartTime.isAfter(template.getBreakTimeStart())
                        ? workStartTime : template.getBreakTimeStart();
                java.time.LocalTime overlapEnd = workEndTime.isBefore(template.getBreakTimeEnd())
                        ? workEndTime : template.getBreakTimeEnd();

                if (overlapStart.isBefore(overlapEnd)) {
                    int deductionMin = (int) java.time.temporal.ChronoUnit.MINUTES.between(overlapStart, overlapEnd);
                    if (deductionMin > 0) {
                        BigDecimal deductedHours = BigDecimal.valueOf(deductionMin)
                                .divide(SIXTY, 4, RoundingMode.HALF_UP);
                        result.add(ComputedBreakDeduction.builder()
                                .source("TEMPLATE")
                                .label(template.getLabelFr())
                                .durationMin(deductionMin)
                                .deductedHours(deductedHours)
                                .appliesToDays(template.getAppliesToDays())
                                .breakTimeStart(template.getBreakTimeStart())
                                .breakTimeEnd(template.getBreakTimeEnd())
                                .build());
                    }
                }
                // If no overlap → no deduction (employee didn't work during this break window)

            } else {
                // Fallback: original hours-based logic (no time range defined)
                if (template.getMinWorkHoursTrigger() != null
                        && grossHours.compareTo(template.getMinWorkHoursTrigger()) < 0) continue;

                BigDecimal deductedHours = BigDecimal.valueOf(template.getDurationMin())
                        .divide(SIXTY, 4, RoundingMode.HALF_UP);
                result.add(ComputedBreakDeduction.builder()
                        .source("TEMPLATE")
                        .label(template.getLabelFr())
                        .durationMin(template.getDurationMin())
                        .deductedHours(deductedHours)
                        .appliesToDays(template.getAppliesToDays())
                        .breakTimeStart(template.getBreakTimeStart())
                        .breakTimeEnd(template.getBreakTimeEnd())
                        .build());
            }
        }

        // Apply legal rules (if no template already covers the threshold)
        for (BreakLegalRule rule : legalRules) {
            if (!appliesToDay(rule.getAppliesToDays(), workDate, entityWeekendDays)) continue;
            if (rule.getMaxWorkHours() != null
                    && grossHours.compareTo(rule.getMaxWorkHours()) > 0) continue;

            // Avoid double-counting if a template already covers this duration
            boolean alreadyCovered = result.stream().anyMatch(d ->
                    d.getDurationMin() >= rule.getDeductionMin());
            if (alreadyCovered) continue;

            BigDecimal deductedHours = BigDecimal.valueOf(rule.getDeductionMin())
                    .divide(SIXTY, 4, RoundingMode.HALF_UP);

            result.add(ComputedBreakDeduction.builder()
                    .source("LEGAL_RULE")
                    .label(rule.getLabelFr())
                    .durationMin(rule.getDeductionMin())
                    .deductedHours(deductedHours)
                    .appliesToDays(rule.getAppliesToDays())
                    .build());
        }

        return result;
    }

    /**
     * Checks if a rule/template applies to the given work date.
     * Uses entity-specific weekend days — NOT hardcoded SAT/SUN.
     *
     * @param appliesToDays "ALL", "WEEKDAYS", "WEEKEND", or comma-separated day codes "MON,TUE"
     * @param date the work date
     * @param entityWeekendDays entity-specific weekend DayOfWeek set
     */
    boolean appliesToDay(String appliesToDays, LocalDate date, Set<DayOfWeek> entityWeekendDays) {
        if (appliesToDays == null || appliesToDays.isEmpty() || "ALL".equalsIgnoreCase(appliesToDays)) {
            return true;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if ("WEEKDAYS".equalsIgnoreCase(appliesToDays)) {
            return !entityWeekendDays.contains(dow);
        }
        if ("WEEKEND".equalsIgnoreCase(appliesToDays)) {
            return entityWeekendDays.contains(dow);
        }
        // Specific day codes: "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"
        String dayCode = dow.name().substring(0, 3).toUpperCase();
        for (String part : appliesToDays.split(",")) {
            if (part.trim().equalsIgnoreCase(dayCode)) return true;
        }
        return false;
    }

    /** Parse pays_weekends.day varchar values to DayOfWeek. */
    public static DayOfWeek parseDayVarchar(String day) {
        if (day == null) return null;
        return switch (day.toUpperCase().trim()) {
            case "MONDAY"    -> DayOfWeek.MONDAY;
            case "TUESDAY"   -> DayOfWeek.TUESDAY;
            case "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THURSDAY"  -> DayOfWeek.THURSDAY;
            case "FRIDAY"    -> DayOfWeek.FRIDAY;
            case "SATURDAY"  -> DayOfWeek.SATURDAY;
            case "SUNDAY"    -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
