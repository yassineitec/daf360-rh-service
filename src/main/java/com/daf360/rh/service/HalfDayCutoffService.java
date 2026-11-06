package com.daf360.rh.service;

import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * « Today, from the instant the employee is asking »: a day off or of remote work may be asked
 * for any slot that has not started yet. Today's full day or morning while the working day has
 * not begun, today's afternoon while it is still morning — the manager still learns of it
 * before it starts.
 *
 * WHEN EACH SLOT STARTS comes from the employee's own regime (RegimeResolutionService), in the
 * regime's zone:
 *   · the day (and the morning) starts at the regime's start time;
 *   · the afternoon starts at the end of the break that spans the middle of the day, when one
 *     does (08:00–17:00 with a 12:00–13:00 lunch → 13:00), otherwise at the midpoint of the day
 *     (08:00–16:00 → 12:00).
 *
 * No regime, no hours or no zone → today is closed. An invented 08:00 or 12:00 in the server's
 * UTC clock would open or close the window an hour (Tunisia) to nine (Dubai) off; refusing is
 * the honest fallback, and the employee still has tomorrow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HalfDayCutoffService {

    private final RegimeResolutionService regimes;

    /** Today's slot starts in the regime's zone. */
    private record TodaySlots(ZonedDateTime dayStart, ZonedDateTime afternoonStart) {
        ZonedDateTime now() {
            return ZonedDateTime.now(dayStart.getZone());
        }
    }

    /**
     * The instant the employee's afternoon starts today, or null when it cannot be worked out
     * or has already passed. Sent to the forms, which compare it with the browser's clock.
     */
    public OffsetDateTime todayAfternoonStart(Long userId) {
        TodaySlots s = slots(userId);
        if (s == null || !s.now().isBefore(s.afternoonStart())) return null;
        return s.afternoonStart().toOffsetDateTime();
    }

    /**
     * The instant the employee's working day (and morning) starts today, or null when it cannot
     * be worked out or has already passed. Sent to the forms like the afternoon one.
     */
    public OffsetDateTime todayDayStart(Long userId) {
        TodaySlots s = slots(userId);
        if (s == null || !s.now().isBefore(s.dayStart())) return null;
        return s.dayStart().toOffsetDateTime();
    }

    /** True when {@code date} is today for the employee and their afternoon has not started. */
    public boolean afternoonStillAhead(Long userId, LocalDate date) {
        TodaySlots s = slots(userId);
        return s != null
                && s.afternoonStart().toLocalDate().equals(date)
                && s.now().isBefore(s.afternoonStart());
    }

    /**
     * True when {@code date} is today for the employee and the slot {@code category} asks for
     * has not started: the afternoon for HALF_DAY_AFTERNOON, the day itself for anything else
     * (a full day, a morning, or the first day of a period).
     */
    public boolean slotStillAhead(Long userId, LocalDate date, LeaveCategory category) {
        TodaySlots s = slots(userId);
        if (s == null || !s.dayStart().toLocalDate().equals(date)) return false;
        ZonedDateTime start = category == LeaveCategory.HALF_DAY_AFTERNOON ? s.afternoonStart() : s.dayStart();
        return s.now().isBefore(start);
    }

    /** The employee's today — in the regime's zone when there is one, the server's otherwise. */
    public LocalDate today(Long userId) {
        TodaySlots s = slots(userId);
        return s != null ? s.dayStart().toLocalDate() : LocalDate.now();
    }

    private TodaySlots slots(Long userId) {
        if (userId == null) return null;
        ResolvedRegimeDto r;
        try {
            r = regimes.resolveForUser(userId);
        } catch (RuntimeException e) {
            // No HR profile — a test account, an external. No regime, no exception.
            return null;
        }
        if (r == null || r.getStartTime() == null || r.getEndTime() == null
                || r.getTimezone() == null || !r.getEndTime().isAfter(r.getStartTime())) {
            return null;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(r.getTimezone());
        } catch (RuntimeException e) {
            log.warn("Regime {} carries an unreadable zone « {} »", r.getRegimeId(), r.getTimezone());
            return null;
        }
        LocalTime noon = midpoint(r.getStartTime(), r.getEndTime());
        LocalTime afternoon = noon;
        if (r.getBreaks() != null) {
            for (ResolvedRegimeDto.BreakWindow b : r.getBreaks()) {
                LocalTime from = parse(b.getStart());
                LocalTime to = parse(b.getEnd());
                // The break that holds the middle of the day ends the morning.
                if (from != null && to != null && !noon.isBefore(from) && noon.isBefore(to)) {
                    afternoon = to;
                    break;
                }
            }
        }
        LocalDate today = LocalDate.now(zone);
        return new TodaySlots(ZonedDateTime.of(today, r.getStartTime(), zone),
                              ZonedDateTime.of(today, afternoon, zone));
    }

    private static LocalTime midpoint(LocalTime start, LocalTime end) {
        int a = start.toSecondOfDay();
        int b = end.toSecondOfDay();
        return LocalTime.ofSecondOfDay(a + (b - a) / 2);
    }

    private static LocalTime parse(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return null;
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
