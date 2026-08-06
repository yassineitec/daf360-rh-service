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
    /** Seasonal window; non-null means this regime can override the entity's others. */
    private java.time.LocalDate seasonalFrom;
    private java.time.LocalDate seasonalTo;
    /** IANA timezone override; null = the regime runs on its entity's clock. */
    private String timezone;
}
