package com.daf360.rh.dto.mission;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** RH's answer to an employee's ask. */
@Data
public class ResolveChangeRequestDto {

    /** true = accept (period moved / mission cancelled), false = refuse. */
    @NotNull
    private Boolean accept;

    @Size(max = 1000)
    private String notes;
}
