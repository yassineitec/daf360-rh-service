package com.daf360.rh.dto.regime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AssignRegimeToEmployeeRequest {
    @NotNull private Long regimeId;
    @NotNull private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @NotBlank private String reason;
}
