package com.daf360.rh.dto.regime;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class ResolvedRegimeDto {
    private Long regimeId;
    private String regimeCode;
    private String regimeLabelFr;
    private String regimeLabelEn;
    private BigDecimal hoursPerWeek;
    private Integer daysPerWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean isFlexible;
    private Integer breakDurationMin;
    private Boolean overtimeAllowed;
    private BigDecimal maxHoursPerDay;
    private String assignmentLevel;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
