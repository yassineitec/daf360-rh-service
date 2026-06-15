package com.daf360.rh.dto.regime;

import java.time.LocalDate;
import java.util.List;

public record WeeklyScheduleDto(
        LocalDate weekStart,
        LocalDate weekEnd,
        double totalExpectedHours,
        List<DaySchedule> days
) {
    public record DaySchedule(
            LocalDate date,
            String dayName,
            boolean isWorkDay,
            double expectedHours
    ) {}
}
