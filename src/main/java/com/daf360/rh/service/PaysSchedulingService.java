package com.daf360.rh.service;

import com.daf360.rh.dto.ref.PaysSchedulingDto;
import com.daf360.rh.dto.ref.UpdatePaysSchedulingRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A country's working calendar: which days are its weekend, and the two leave-scheduling
 * delays that apply to every type that does not override them.
 *
 * WHY THIS EXISTS
 * -----------------------------------------------------------------------------
 * `pays_weekends` had no write path anywhere in this service — five readers, no editor — so
 * an entity's rest days could only be changed with hand-written SQL. That is the table the
 * congé day-count, the presence automation, the break deduction and the regime resolver all
 * consult, and it silently falls back to Saturday/Sunday when a country has no rows. A
 * company opening in the Gulf would have been quietly charged the wrong leave until somebody
 * noticed the numbers.
 *
 * The two delays sit here with it because they are one policy: how this country schedules
 * time off. V109 moved the authoritative values onto the leave TYPE; these stay as the
 * default a type inherits when it specifies nothing.
 *
 * JdbcTemplate rather than JPA: `pays_weekends` is an element collection with no entity of
 * its own in this service, and `pays` is mapped read-only for reference data. A three-column
 * editor does not justify introducing either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaysSchedulingService {

    private final JdbcTemplate jdbc;

    /**
     * Entities worth configuring: the ones that employ somebody, plus any that already carry
     * configuration.
     *
     * NOT all 194 countries. The reference table is a full ISO list, and a picker of 194 rows
     * to find the two you operate in is a worse screen than one that shows those two. A
     * country becomes visible here the moment its first employee is assigned to it.
     */
    @Transactional(readOnly = true)
    public List<PaysSchedulingDto> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.id, p.french_label, p.english_label, p.iso_code, p.timezone,
                       p.advance_notice_days, p.leave_gap_days,
                       (SELECT COUNT(*) FROM [dbo].[Users] u
                         WHERE u.pays_id = p.id AND ISNULL(u.is_employee, 1) = 1) AS employees
                FROM [dbo].[pays] p
                WHERE ISNULL(p.deleted, 0) = 0
                  AND (EXISTS (SELECT 1 FROM [dbo].[Users] u WHERE u.pays_id = p.id)
                       OR EXISTS (SELECT 1 FROM [dbo].[pays_weekends] w WHERE w.pays_id = p.id)
                       OR p.advance_notice_days IS NOT NULL
                       OR p.leave_gap_days IS NOT NULL)
                ORDER BY p.french_label
                """);

        Map<Long, Set<String>> weekends = weekendsByPays();

        List<PaysSchedulingDto> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            Set<String> days = weekends.getOrDefault(id, Set.of());
            out.add(new PaysSchedulingDto(
                    id,
                    (String) r.get("french_label"),
                    (String) r.get("english_label"),
                    (String) r.get("iso_code"),
                    (String) r.get("timezone"),
                    new ArrayList<>(days),
                    // The screen has to be able to say "falling back", not just show two days
                    // that look configured. This is the same test WorkingDayCalculator makes.
                    days.isEmpty(),
                    (Integer) r.get("advance_notice_days"),
                    (Integer) r.get("leave_gap_days"),
                    ((Number) r.get("employees")).intValue()));
        }
        return out;
    }

    /** pays_id -> its weekend day names, in week order so the UI never has to sort. */
    private Map<Long, Set<String>> weekendsByPays() {
        Map<Long, Set<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT pays_id, [day] FROM [dbo].[pays_weekends]", rs -> {
            String raw = rs.getString("day");
            if (raw == null || raw.isBlank()) return;
            out.computeIfAbsent(rs.getLong("pays_id"), k -> new LinkedHashSet<>())
               .add(raw.trim().toUpperCase());
        });
        // Order by DayOfWeek rather than alphabetically: FRIDAY, SATURDAY reads as a weekend;
        // SATURDAY, SUNDAY sorted as text happens to be right and FRIDAY, SATURDAY does not.
        out.replaceAll((k, v) -> v.stream()
                .filter(PaysSchedulingService::isDayName)
                .sorted(java.util.Comparator.comparingInt(d -> DayOfWeek.valueOf(d).getValue()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return out;
    }

    private static boolean isDayName(String raw) {
        try { DayOfWeek.valueOf(raw); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    /**
     * Replace a country's whole calendar configuration.
     *
     * THE WEEKEND SET IS REPLACED, NOT DIFFED. The join carries nothing but the pair, so
     * delete-then-insert is equivalent and cannot drift.
     *
     * AN EMPTY WEEKEND IS REFUSED. `WorkingDayCalculator` reads no rows as "not configured"
     * and falls back to Saturday/Sunday — so saving an empty set would look like "this country
     * works seven days a week" and silently behave as Sat/Sun instead. A country that really
     * has no rest day is not something this screen can express, and pretending otherwise would
     * be worse than refusing.
     *
     * The two delays accept null, which clears them back to "no country default".
     */
    @Transactional
    public PaysSchedulingDto update(Long paysId, UpdatePaysSchedulingRequest req, Long actorId) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM [dbo].[pays] WHERE id = ? AND ISNULL(deleted, 0) = 0",
                Integer.class, paysId);
        if (exists == null || exists == 0) {
            throw new AppException(ErrorCode.NOT_FOUND, "Entité introuvable : " + paysId);
        }

        Set<String> days = new LinkedHashSet<>();
        for (String raw : req.getWeekendDays() == null ? List.<String>of() : req.getWeekendDays()) {
            if (raw == null || raw.isBlank()) continue;
            String name = raw.trim().toUpperCase();
            if (!isDayName(name)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION, "Jour inconnu : " + raw);
            }
            days.add(name);
        }
        if (days.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une entité doit avoir au moins un jour de repos. Sans aucun jour, "
                    + "le calcul des congés retombe sur samedi/dimanche sans le dire.");
        }
        if (days.size() >= 7) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une entité ne peut pas avoir sept jours de repos : aucun congé ne coûterait de jour.");
        }
        if (nonNegative(req.getAdvanceNoticeDays()) || nonNegative(req.getLeaveGapDays())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Les délais ne peuvent pas être négatifs.");
        }

        Set<String> before = weekendsByPays().getOrDefault(paysId, Set.of());

        jdbc.update("DELETE FROM [dbo].[pays_weekends] WHERE pays_id = ?", paysId);
        for (String day : days) {
            jdbc.update("INSERT INTO [dbo].[pays_weekends] (pays_id, [day]) VALUES (?, ?)", paysId, day);
        }
        // Written as literals when null: Spring hands an untyped null to setNull(Types.NULL),
        // which the SQL Server driver rejects — the same trap the balances editor hit.
        jdbc.update("UPDATE [dbo].[pays] SET advance_notice_days = "
                        + (req.getAdvanceNoticeDays() == null ? "NULL" : "?")
                        + ", leave_gap_days = "
                        + (req.getLeaveGapDays() == null ? "NULL" : "?")
                        + " WHERE id = ?",
                argsFor(req, paysId));

        log.info("Calendar of pays {} changed by {}: weekend {} -> {}, notice {}, gap {}",
                paysId, actorId, before, days,
                req.getAdvanceNoticeDays(), req.getLeaveGapDays());

        return list().stream().filter(p -> p.paysId().equals(paysId)).findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Entité introuvable après mise à jour"));
    }

    /** Only the delays that were actually sent, then the id — matching the SQL built above. */
    private Object[] argsFor(UpdatePaysSchedulingRequest req, Long paysId) {
        List<Object> args = new ArrayList<>(3);
        if (req.getAdvanceNoticeDays() != null) args.add(req.getAdvanceNoticeDays());
        if (req.getLeaveGapDays() != null) args.add(req.getLeaveGapDays());
        args.add(paysId);
        return args.toArray();
    }

    private boolean nonNegative(Integer v) {
        return v != null && v < 0;
    }
}
