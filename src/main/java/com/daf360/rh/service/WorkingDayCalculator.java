package com.daf360.rh.service;

import com.daf360.rh.domain.Holiday;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How many days a leave request actually costs.
 *
 * WHY THIS IS ON THE SERVER
 * -----------------------------------------------------------------------------
 * In the timesheet this was computed entirely in the browser — `conge.config.ts` held its
 * own `isWeekend` and `isHoliday` and counted the range — and the backend subtracted
 * whatever number arrived from the employee's balance without checking it. Any caller able
 * to POST a request could therefore choose what it cost them.
 *
 * The rule is ported unchanged; only where it runs moves. The client may still compute a
 * figure to show while the form is being filled, but the server recomputes on submit and
 * that value is the one stored.
 *
 * WEEKENDS ARE PER COUNTRY
 * -----------------------------------------------------------------------------
 * `pays_weekends` is not decoration: Tunisia rests Saturday and Sunday, Egypt Friday and
 * Saturday. Counting a Tunisian weekend for an Egyptian employee gets both the cost and the
 * dates wrong, and the error is invisible — the total is merely a day or two out.
 *
 * A country with no rows falls back to Saturday/Sunday rather than counting every day as
 * working; it is the likelier intent, and it is logged so the gap is visible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingDayCalculator {

    private static final Set<DayOfWeek> DEFAULT_WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final JdbcTemplate jdbcTemplate;
    private final HolidayRepository holidayRepository;

    /**
     * Billable days for a request.
     *
     * The three single-day categories return their fixed duration without inspecting the
     * calendar: a half-day is half a day whether or not it is a Tuesday, and an employee who
     * books a full day on a public holiday has made a mistake the form should catch — not one
     * this method should silently price at zero.
     *
     * Only {@link LeaveCategory#MULTIPLE_DAYS} counts the range.
     */
    public BigDecimal billableDays(LocalDate from, LocalDate to, LeaveCategory category, Long paysId) {
        BigDecimal fixed = category.fixedDays();
        if (fixed != null) {
            return fixed;
        }
        return BigDecimal.valueOf(countWorkingDays(from, to, paysId));
    }

    /**
     * Working days in an inclusive range, excluding that country's weekend and its public
     * holidays.
     *
     * Returns 0 for a range that falls entirely on non-working days. That is a legitimate
     * answer, not an error — it is why {@link LeaveCategory#fixedDays()} returns null rather
     * than zero for MULTIPLE_DAYS, so the two cases stay distinguishable.
     */
    public long countWorkingDays(LocalDate from, LocalDate to, Long paysId) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0L;
        }

        Set<DayOfWeek> weekend = weekendFor(paysId);
        Set<LocalDate> holidays = holidaysBetween(paysId, from, to);

        long days = 0L;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (weekend.contains(d.getDayOfWeek())) continue;
            if (holidays.contains(d)) continue;
            days++;
        }
        return days;
    }

    /** True when this date is neither weekend nor holiday in that country. */
    public boolean isWorkingDay(LocalDate date, Long paysId) {
        return !weekendFor(paysId).contains(date.getDayOfWeek())
                && !holidaysBetween(paysId, date, date).contains(date);
    }

    /**
     * The country's rest days.
     *
     * Read through JdbcTemplate rather than an entity because `pays_weekends` is a two-column
     * association with no identity of its own — the same reason MissionService reads Users
     * that way.
     */
    public Set<DayOfWeek> weekendFor(Long paysId) {
        if (paysId == null) {
            return DEFAULT_WEEKEND;
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT day FROM pays_weekends WHERE pays_id = ?", String.class, paysId);

        if (rows.isEmpty()) {
            log.warn("No pays_weekends rows for pays_id={} — falling back to SATURDAY/SUNDAY. "
                    + "Leave totals for this country are a guess until the rows are seeded.", paysId);
            return DEFAULT_WEEKEND;
        }

        Set<DayOfWeek> weekend = EnumSet.noneOf(DayOfWeek.class);
        for (String raw : rows) {
            if (raw == null) continue;
            try {
                weekend.add(DayOfWeek.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                // A value the enum does not know is a data error, not a reason to fail a
                // leave request. Skip it loudly and carry on with the days we do understand.
                log.warn("pays_weekends holds '{}' for pays_id={}, which is not a DayOfWeek — ignored.", raw, paysId);
            }
        }
        return weekend.isEmpty() ? DEFAULT_WEEKEND : weekend;
    }

    /**
     * ISO date to holiday name, for the request form's date picker and cost preview.
     *
     * The same rows {@link #countWorkingDays} excludes, exposed so the client can grey those
     * days out and count as the server will. Two systems agreeing on which days are working
     * days only happens if one of them is told.
     */
    public java.util.Map<String, String> holidayNames(Long paysId, LocalDate from, LocalDate to, String lang) {
        if (paysId == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Holiday h : holidayRepository.findByPaysIdAndDateHolidayBetween(paysId, from, to)) {
            if (h.getDateHoliday() == null) continue;
            String label = "fr".equalsIgnoreCase(lang) ? h.getFrenchLabel() : h.getEnglishLabel();
            if (label == null || label.isBlank()) {
                label = h.getFrenchLabel() != null ? h.getFrenchLabel() : h.getEnglishLabel();
            }
            out.put(h.getDateHoliday().toString(), label == null ? "" : label);
        }
        return out;
    }

    /** Public holidays for that country inside the range, as dates. */
    private Set<LocalDate> holidaysBetween(Long paysId, LocalDate from, LocalDate to) {
        if (paysId == null) {
            return Set.of();
        }
        List<Holiday> rows = holidayRepository.findByPaysIdAndDateHolidayBetween(paysId, from, to);
        Set<LocalDate> dates = new HashSet<>(rows.size());
        for (Holiday h : rows) {
            if (Boolean.TRUE.equals(h.getDeleted())) continue;
            if (h.getDateHoliday() != null) dates.add(h.getDateHoliday());
        }
        return dates;
    }
}
