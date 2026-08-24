package com.daf360.rh.dto.mission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * The employee's ask on their own mission. The type is not carried here: the two endpoints
 * are distinct ({@code /change-request} and {@code /cancel-request}), so a period change can
 * never be submitted as a cancellation by flipping a field.
 */
@Data
public class MissionChangeRequestCreate {

    /** Required for a period change, ignored for a cancellation. */
    private LocalDate requestedStartDate;
    private LocalDate requestedEndDate;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
