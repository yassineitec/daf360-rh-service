package com.daf360.rh.dto.break_;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ComputedBreakDeduction {
    private String source;       // "TEMPLATE" or "LEGAL_RULE"
    private String label;
    private Integer durationMin;
    private BigDecimal deductedHours;
    private String appliesToDays;
    private java.time.LocalTime breakTimeStart;
    private java.time.LocalTime breakTimeEnd;
}
