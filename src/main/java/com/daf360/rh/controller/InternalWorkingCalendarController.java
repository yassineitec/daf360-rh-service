package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.repository.LeaveRequestRepository;
import com.daf360.rh.service.WorkingDayCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The working calendar of one employee, for the timesheet application.
 *
 * The timesheet decides how many hours a week may hold, and how many a day may hold, from
 * three facts per day: is it a rest day, is it a public holiday, is the employee on approved
 * leave (whole or half day). It used to read all three from its own copy of the leave tables.
 * Congés, holidays and weekends belong to RH now (TIMESHEET-INTEGRATION-DECISIONS.md, C0.2),
 * so this endpoint is the one source it reads instead — and the reason the timesheet's own
 * absence / holiday modules can be deleted.
 *
 * WHY INTERNAL (X-Internal-Key), NOT THE USER'S TOKEN
 *   The timesheet has already authorised the request against its own permission codes before
 *   it computes anything — a manager validating a team's hours asks about someone else. Only
 *   dates and a whole/half flag come back: no leave type, no reason.
 *
 * WHY E-MAIL
 *   The timesheet's user ids are not DAF360_HR's; the two are matched on e-mail (C0.5).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalWorkingCalendarController {

    private static final String HEADER = "X-Internal-Key";
    /** A request spans a week or two; this only stops a runaway range from scanning years. */
    private static final int MAX_DAYS = 93;

    private final WorkingDayCalculator calendar;
    private final LeaveRequestRepository leaveRequests;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    public enum LeaveFraction { FULL, HALF }

    public record CalendarDay(LocalDate date, boolean weekend, boolean holiday, LeaveFraction leave) {}

    public record WorkingCalendar(Long userId, Long paysId, Set<DayOfWeek> weekendDays, List<CalendarDay> days) {}

    /**
     * GET /api/hr/internal/working-calendar?email=…&from=YYYY-MM-DD&to=YYYY-MM-DD
     * Every date in [from, to] inclusive. 404 when no DAF360_HR user has that e-mail.
     */
    @GetMapping("/api/hr/internal/working-calendar")
    public ResponseEntity<WorkingCalendar> workingCalendar(
            @RequestParam String email,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        if (to.isBefore(from) || from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from/to must be ordered and at most " + MAX_DAYS + " days apart");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, pays_id FROM [dbo].[Users] WHERE LOWER(LTRIM(RTRIM(email))) = LOWER(LTRIM(RTRIM(?)))",
                email);
        if (rows.isEmpty()) {
            log.warn("Working calendar asked for '{}', who has no DAF360_HR account", email);
            return ResponseEntity.notFound().build();
        }
        Long userId = ((Number) rows.get(0).get("id")).longValue();
        Number pays = (Number) rows.get(0).get("pays_id");
        Long paysId = pays == null ? null : pays.longValue();

        Set<DayOfWeek> weekend = calendar.weekendFor(paysId);
        Set<LocalDate> holidays = calendar.holidaysBetween(paysId, from, to);
        // findOverlapping also returns pending requests (the home calendar shows them);
        // only an APPROVED congé removes working time.
        List<LeaveRequest> approved = leaveRequests.findOverlapping(userId, from, to).stream()
                .filter(l -> l.getEtatDemande() == DemandeEtat.VALIDE)
                .toList();

        List<CalendarDay> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(new CalendarDay(day, weekend.contains(day.getDayOfWeek()), holidays.contains(day),
                    leaveOn(day, approved)));
        }
        return ResponseEntity.ok(new WorkingCalendar(userId, paysId, weekend, days));
    }

    /** First approved congé covering the day; a half-day category counts as half. */
    private static LeaveFraction leaveOn(LocalDate day, List<LeaveRequest> approved) {
        for (LeaveRequest l : approved) {
            if (day.isBefore(l.getDateDebut()) || day.isAfter(l.getDateFin())) continue;
            LeaveCategory c = l.getCategory();
            return (c == LeaveCategory.HALF_DAY_MORNING || c == LeaveCategory.HALF_DAY_AFTERNOON)
                    ? LeaveFraction.HALF : LeaveFraction.FULL;
        }
        return null;
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        byte[] x = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = provided == null ? new byte[0] : provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(x, y)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }
}
