package com.daf360.rh.dto.regime;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AssignRegimeToRoleRequest {
    @NotNull private Long regimeId;
    @NotNull private Long roleId;
    @NotNull private Long paysId;
    @NotNull private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String notes;
}
