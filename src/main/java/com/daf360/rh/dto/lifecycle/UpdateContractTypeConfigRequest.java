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
    @Min(value = 0,  message = "Le nombre de renouvellements ne peut pas être négatif.")
    @Max(value = 10, message = "Le nombre de renouvellements ne peut pas dépasser 10.")
    private Integer trialMaxRenewals;
    /** Action codes (ContractDecisionAction) offered at the end of the trial; null = no change. */
    private java.util.List<String> trialDecisions;
    /** Action codes offered at the planned end of the contract; null = no change. */
    private java.util.List<String> endDecisions;
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
