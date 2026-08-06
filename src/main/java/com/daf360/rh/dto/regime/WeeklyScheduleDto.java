package com.daf360.rh.dto.regime;

import java.time.LocalDate;
import java.util.List;

public record WeeklyScheduleDto(
        LocalDate weekStart,
        LocalDate weekEnd,
        double totalExpectedHours,
        List<DaySchedule> days,
        /**
         * IANA zone the week was computed in — the entity's, or the regime's override.
         * The week boundaries are calendar dates, so which "today" they were derived from
         * depends on the zone; clients rendering the week must not re-derive it from the
         * browser. Null when the entity has no timezone configured.
         */
        String timezone
) {
    public record DaySchedule(
            LocalDate date,
            String dayName,
            boolean isWorkDay,
            double expectedHours
    ) {}
}
