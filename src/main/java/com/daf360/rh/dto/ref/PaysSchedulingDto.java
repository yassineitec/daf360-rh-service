package com.daf360.rh.dto.ref;

import java.util.List;

/**
 * An entity's working calendar, as the administration screen reads it.
 *
 * {@code usingDefaultWeekend} is the field that earns this DTO: an entity with no
 * `pays_weekends` rows is not "working seven days a week" — {@code WorkingDayCalculator}
 * falls back to Saturday/Sunday for it. Returning the fallback days alone would show two
 * configured days that nobody chose, and an administrator would have no way to tell a
 * deliberate Sat/Sun from a country nobody has set up. The screen needs both facts.
 */
public record PaysSchedulingDto(
        Long paysId,
        String frenchLabel,
        String englishLabel,
        String isoCode,
        /** IANA zone, edited on the regimes screen. Shown here only as context. */
        String timezone,
        /** DayOfWeek names in week order. Empty when nothing is configured. */
        List<String> weekendDays,
        /** True when the list above is empty and Saturday/Sunday is being assumed. */
        boolean usingDefaultWeekend,
        /** Country default inherited by any leave type that sets no value of its own (V109). */
        Integer advanceNoticeDays,
        Integer leaveGapDays,
        /** How many real people this applies to — the reason to get it right. */
        int employees
) {}
