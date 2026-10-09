package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateContractTypeConfigRequest {

    private Integer trialPeriodDaysStandard;
    private Integer trialPeriodDaysManager;
    private Boolean trialPeriodRenewable;
    @Min(value = 0,   message = "Le délai d'alerte ne peut pas être négatif.")
    @Max(value = 365, message = "Le délai d'alerte ne peut pas dépasser 365 jours.")
    private Integer alertDaysBeforeExpiry;
    @Min(value = 0,   message = "Le délai d'alerte ne peut pas être négatif.")
    @Max(value = 365, message = "Le délai d'alerte ne peut pas dépasser 365 jours.")
    private Integer alertDaysBeforeTrialEnd;
    private BigDecimal indemnityRatePct;
    private Boolean indemnityApplicable;
    private Integer civpMaxAge;
    private Integer civpMaxDurationMonths;
    private Boolean civpAnetiRequired;
    private Integer stageMaxDurationMonths;
    private Integer stageMinGratificationMonths;
}
