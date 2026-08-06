package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * The passation window, broken down — computed on read, stored nowhere (V65).
 *
 * `workingDays` excludes the days the employee is on validated leave and `leaveDays` counts
 * exactly those, so the two add up to `totalDays` without either being double-counted. The
 * breakdown is the point: "22 j ouvrables + 3 j congés" tells the manager how much handover
 * time actually exists, where a single 25 would not.
 */
@Data
@Builder
public class OffboardingHandoverDurationDto {

    /** `handover_started_at` — the manager's chosen start. */
    private LocalDate startDate;

    /** `last_working_day` — the window always ends on the departure. */
    private LocalDate endDate;

    /** Working days in the window, per the pays' weekend days, minus days on leave. */
    private Integer workingDays;

    /** Working days inside the window covered by a validated absence. */
    private Integer leaveDays;

    private Integer totalDays;
}
