package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.ContractTypeConfig;
import com.daf360.rh.lists.ConfigurableListValue;

import java.util.List;

/**
 * The « Décider » rules a contract follows, resolved in this order:
 * <ol>
 *   <li>its contract type's own rules (Listes configurables › Type de contrat), field by field;</li>
 *   <li>else its nature's row of contract_type_config (Échéances de contrat);</li>
 *   <li>else the nature's built-in defaults ({@link ContractDecisionAction#defaults}).</li>
 * </ol>
 *
 * @param inherited true when the type has no rule of its own (everything comes from the nature)
 */
public record ContractRules(
        boolean trialRenewable,
        int trialMaxRenewals,
        List<ContractDecisionAction> trialDecisions,
        List<ContractDecisionAction> endDecisions,
        boolean inherited) {

    public static ContractRules resolve(ConfigurableListValue type, ContractTypeConfig config, String nature) {
        boolean own = type != null && type.hasOwnDecisionRules();

        Boolean renewable = own && type.getTrialRenewable() != null ? type.getTrialRenewable()
            : config != null ? config.getTrialPeriodRenewable() : null;
        Integer max = own && type.getTrialMaxRenewals() != null ? type.getTrialMaxRenewals()
            : config != null ? config.getTrialMaxRenewals() : null;

        List<ContractDecisionAction> trial = own ? ContractDecisionAction.parseCsv(type.getTrialDecisions()) : null;
        if (trial == null) trial = EmployeeLifecycleService.decisionsOf(config, nature, ContractDecisionAction.Moment.TRIAL_END);
        List<ContractDecisionAction> end = own ? ContractDecisionAction.parseCsv(type.getEndDecisions()) : null;
        if (end == null) end = EmployeeLifecycleService.decisionsOf(config, nature, ContractDecisionAction.Moment.CONTRACT_END);

        return new ContractRules(Boolean.TRUE.equals(renewable), max != null ? max : 1, trial, end, !own);
    }

    public List<ContractDecisionAction> decisionsAt(ContractDecisionAction.Moment moment) {
        return moment == ContractDecisionAction.Moment.TRIAL_END ? trialDecisions : endDecisions;
    }
}
