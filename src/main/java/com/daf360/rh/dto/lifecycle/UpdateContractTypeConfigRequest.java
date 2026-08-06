package com.daf360.rh.dto.lifecycle;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateContractTypeConfigRequest {

    private Integer trialPeriodDaysStandard;
    private Integer trialPeriodDaysManager;
    private Boolean trialPeriodRenewable;
    /** V64 — préavis in calendar days, per pays × contract type. */
    private Integer noticePeriodDaysStandard;
    private Integer noticePeriodDaysManager;
    private Integer alertDaysBeforeExpiry;
    private BigDecimal indemnityRatePct;
    private Boolean indemnityApplicable;
    private Integer civpMaxAge;
    private Integer civpMaxDurationMonths;
    private Boolean civpAnetiRequired;
    private Integer stageMaxDurationMonths;
    private Integer stageMinGratificationMonths;
}
