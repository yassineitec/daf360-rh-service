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
import java.util.Map;
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
    /**
     * The Nth working day after {@code from}, skipping that country's weekends and holidays.
     *
     * Used by the advance-notice and leave-gap rules, so that "three days' notice" means three
     * days somebody could actually have acted on — a Friday request for a Monday start gives
     * no notice at all if the weekend counted.
     *
     * `n <= 0` returns `from` unchanged, which is how a disabled rule reads.
     *
     * The window is resolved ONCE for the whole walk rather than per day: the old per-day
     * lookup in the timesheet ran a query for every step.
     */
    public LocalDate addWorkingDays(LocalDate from, int n, Long paysId) {
        if (from == null || n <= 0) {
            return from;
        }
        Set<DayOfWeek> weekend = weekendFor(paysId);
        // Generous upper bound: n working days can never need more than n*2 + 30 calendar
        // days even with a long public-holiday run, and the loop stops as soon as it has n.
        Set<LocalDate> holidays = holidaysBetween(paysId, from, from.plusDays((long) n * 2 + 30));

        LocalDate cursor = from;
        int added = 0;
        while (added < n) {
            cursor = cursor.plusDays(1);
            if (weekend.contains(cursor.getDayOfWeek())) continue;
            if (holidays.contains(cursor)) continue;
            added++;
        }
        return cursor;
    }

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
        for (Map.Entry<LocalDate, Holiday> e : occurrencesBetween(paysId, from, to).entrySet()) {
            Holiday h = e.getValue();
            String label = "fr".equalsIgnoreCase(lang) ? h.getFrenchLabel() : h.getEnglishLabel();
            if (label == null || label.isBlank()) {
                label = h.getFrenchLabel() != null ? h.getFrenchLabel() : h.getEnglishLabel();
            }
            out.put(e.getKey().toString(), label == null ? "" : label);
        }
        return out;
    }

    /** Public holidays for that country inside the range, as dates. */
    private Set<LocalDate> holidaysBetween(Long paysId, LocalDate from, LocalDate to) {
        if (paysId == null) {
            return Set.of();
        }
        return occurrencesBetween(paysId, from, to).keySet();
    }

    /**
     * Every date in [from, to] that is a public holiday, mapped to the row that says so.
     *
     * RECURRENCE IS RESOLVED HERE, AND IT USED NOT TO BE.
     * -------------------------------------------------------------------------------------
     * `is_recurring` is labelled « Récurrent (chaque année) » in the admin screen, was stored
     * and edited — and read by nothing. Every query matched `date_holiday BETWEEN ? AND ?`,
     * so a holiday entered once for 2026 simply stopped existing on 1 January 2027 and every
     * employee was charged a leave day for it. The data shows the workaround: Albania carries
     * nine recurring rows dated 2025 and three dated 2026, re-entered by hand.
     *
     * A recurring row now means the same MONTH AND DAY of every year from its own year
     * onward. Not before it: a holiday created in 2026 did not exist in 2024, and back-dating
     * it would silently re-cost leave already taken and approved.
     *
     * 29 FEBRUARY falls back to 28 February in a common year. The alternative — skipping it —
     * would quietly drop the holiday three years in four.
     *
     * Non-recurring rows keep matching on their exact date, which is right: Eid and Mawlid
     * move with the lunar calendar, and the Egyptian data already marks them accordingly.
     */
    private Map<LocalDate, Holiday> occurrencesBetween(Long paysId, LocalDate from, LocalDate to) {
        if (paysId == null || from == null || to == null || to.isBefore(from)) {
            return Map.of();
        }
        Map<LocalDate, Holiday> out = new java.util.LinkedHashMap<>();

        // The whole country's holidays, not a date slice: a recurring row dated 2020 has to be
        // reachable when the window is 2027, and a BETWEEN on the stored date never finds it.
        for (Holiday h : holidayRepository.findByPaysId(paysId)) {
            if (Boolean.TRUE.equals(h.getDeleted())) continue;
            LocalDate stored = h.getDateHoliday();
            if (stored == null) continue;

            if (!Boolean.TRUE.equals(h.getIsRecurring())) {
                if (!stored.isBefore(from) && !stored.isAfter(to)) out.putIfAbsent(stored, h);
                continue;
            }

            int firstYear = Math.max(stored.getYear(), from.getYear());
            for (int year = firstYear; year <= to.getYear(); year++) {
                LocalDate occurrence = occurrenceIn(stored, year);
                if (!occurrence.isBefore(from) && !occurrence.isAfter(to)) {
                    out.putIfAbsent(occurrence, h);
                }
            }
        }
        return out;
    }

    /** The stored day-and-month placed in {@code year}, clamped for 29 February. */
    private LocalDate occurrenceIn(LocalDate stored, int year) {
        int day = Math.min(stored.getDayOfMonth(),
                           java.time.YearMonth.of(year, stored.getMonth()).lengthOfMonth());
        return LocalDate.of(year, stored.getMonth(), day);
    }
}
