package com.daf360.rh.dto.overtime;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class OvertimeCalculationRequest {
    @NotNull private Long       paysId;
    @NotNull private LocalDate  workDate;
    private LocalTime workStartTime;
    private LocalTime workEndTime;
    @NotNull private BigDecimal grossHours;  // total hours worked that day
}
