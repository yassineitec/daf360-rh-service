package com.daf360.rh.dto.overtime;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class OvertimeCalculationResult {
    private BigDecimal overtimeHours;    // hours considered as overtime
    private BigDecimal normalHours;      // grossHours - overtimeHours
    private String     ruleApplied;      // WEEKEND_ONLY / AFTER_WORK_HOURS / MIXTE
    private String     explanation;      // human-readable explanation
    private boolean    isWeekendDay;
    private String     paysIsoCode;
}
