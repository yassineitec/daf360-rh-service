package com.daf360.rh.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for "which days are the weekend" per entity (pays).
 *
 * Working days MUST come from [pays_weekends], never from days_per_week: Egypt's
 * weekend is FRIDAY+SATURDAY (working week Sunday→Thursday) while Tunisia's is
 * SATURDAY+SUNDAY. Deriving MONDAY–FRIDAY from "5 days per week" is wrong on two
 * days of every Egyptian week.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaysWeekendService {

    /** Monday-first, matching the French day vocabulary the pointage contract uses. */
    private static final DayOfWeek[] WEEK_ORDER = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    };

    private static final String[] FRENCH_DAYS = {
            "LUNDI", "MARDI", "MERCREDI", "JEUDI", "VENDREDI", "SAMEDI", "DIMANCHE"
    };

    private final JdbcTemplate jdbc;

    /** Weekend days for the entity. Falls back to SAT+SUN only when nothing is configured. */
    public Set<DayOfWeek> weekendDays(Long paysId) {
        Set<DayOfWeek> weekends = new HashSet<>();
        if (paysId != null) {
            try {
                List<String> days = jdbc.queryForList(
                        "SELECT [day] FROM [dbo].[pays_weekends] WHERE pays_id = ?",
                        String.class, paysId);
                for (String day : days) {
                    DayOfWeek dow = BreakCalculationEngine.parseDayVarchar(day);
                    if (dow != null) weekends.add(dow);
                }
            } catch (Exception e) {
                log.warn("Could not load pays_weekends for paysId={}: {}", paysId, e.getMessage());
            }
        }
        if (weekends.isEmpty()) {
            log.debug("No weekend configured for paysId={} — defaulting to SATURDAY+SUNDAY", paysId);
            weekends.add(DayOfWeek.SATURDAY);
            weekends.add(DayOfWeek.SUNDAY);
        }
        return weekends;
    }

    /** Working DayOfWeek set = the week minus the entity's weekend. */
    public Set<DayOfWeek> workingDays(Long paysId) {
        Set<DayOfWeek> weekend = weekendDays(paysId);
        Set<DayOfWeek> working = new LinkedHashSet<>();
        for (DayOfWeek d : WEEK_ORDER) {
            if (!weekend.contains(d)) working.add(d);
        }
        return working;
    }

    /**
     * Working days as the French day codes the pointage contract expects
     * (LUNDI…DIMANCHE), Monday-first.
     */
    public List<String> workingDayLabels(Long paysId) {
        Set<DayOfWeek> working = workingDays(paysId);
        return java.util.Arrays.stream(WEEK_ORDER)
                .filter(working::contains)
                .map(PaysWeekendService::frenchLabel)
                .toList();
    }

    public static String frenchLabel(DayOfWeek day) {
        return FRENCH_DAYS[day.getValue() - 1];
    }
}
