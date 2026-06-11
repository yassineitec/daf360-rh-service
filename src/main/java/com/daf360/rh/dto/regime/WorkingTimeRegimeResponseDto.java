package com.daf360.rh.dto.regime;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
public class WorkingTimeRegimeResponseDto {
    private Long      id;
    private Long      paysId;
    private String    code;
    private String    labelFr;
    private String    labelEn;
    private BigDecimal hoursPerWeek;
    private Integer   daysPerWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private Boolean   isFlexible;
    private Boolean   isDefault;
    private Boolean   isActive;
}
