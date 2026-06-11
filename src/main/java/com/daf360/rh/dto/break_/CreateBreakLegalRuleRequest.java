package com.daf360.rh.dto.break_;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateBreakLegalRuleRequest {
    private Long paysId;
    private String labelFr;
    private String labelEn;
    private BigDecimal minWorkHours;
    private BigDecimal maxWorkHours;
    private Integer deductionMin;
    private String appliesToDays = "ALL";
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
}
