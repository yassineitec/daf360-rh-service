package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Books the exit interview — the design's **Planifier**.
 *
 * A moment, not a day: an interview is at a time, and the invitation is useless without one.
 * Creates the record when there is none and re-books it when there is, so rescheduling is the
 * same call rather than a delete-and-recreate.
 */
@Data
public class ScheduleExitInterviewDto {

    @NotNull
    private OffsetDateTime scheduledAt;
}
