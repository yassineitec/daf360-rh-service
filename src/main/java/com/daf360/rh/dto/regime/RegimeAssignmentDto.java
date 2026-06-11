package com.daf360.rh.dto.regime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RegimeAssignmentDto {

    @NotNull
    private Long regimeId;

    @NotNull
    private LocalDate startDate;

    /** null = indefinite */
    private LocalDate endDate;

    @NotBlank @Size(max = 255)
    private String reason;
}
