package com.daf360.rh.dto.break_;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class BreakLegalRuleDto {
    private Long id;
    private Long paysId;
    private String labelFr;
    private String labelEn;
    private BigDecimal minWorkHours;
    private BigDecimal maxWorkHours;
    private Integer deductionMin;
    private String appliesToDays;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Boolean isActive;
}
