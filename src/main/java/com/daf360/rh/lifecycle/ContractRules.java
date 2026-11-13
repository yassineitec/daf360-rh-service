package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.ContractTypeConfig;
import com.daf360.rh.lists.ConfigurableListValue;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The « Décider » rules a contract follows, resolved in this order:
 * <ol>
 *   <li>its contract type's own rules (Listes configurables › Type de contrat), field by field —
 *       the type picks CHOICES, i.e. values of the list CONTRACT_DECISION (by code);</li>
 *   <li>else its nature's row of contract_type_config (Échéances de contrat), which picks
 *       ACTIONS — every choice carrying one of them is offered;</li>
 *   <li>else the nature's built-in defaults ({@link ContractDecisionAction#defaults}).</li>
 * </ol>
 *
 * @param trialDecisions actions offered at the end of the trial when the type picks no choices
 * @param endDecisions   same, at the planned end of the contract
 * @param trialChoices   the type's own choices (CONTRACT_DECISION value codes) at the end of the
 *                       trial; null = it follows {@code trialDecisions}
 * @param endChoices     same, at the planned end of the contract
 * @param inherited      true when the type has no rule of its own (everything comes from the nature)
 */
public record ContractRules(
        boolean trialRenewable,
        int trialMaxRenewals,
        List<ContractDecisionAction> trialDecisions,
        List<ContractDecisionAction> endDecisions,
        List<String> trialChoices,
        List<String> endChoices,
        boolean inherited) {

    public static ContractRules resolve(ConfigurableListValue type, ContractTypeConfig config, String nature) {
        boolean own = type != null && type.hasOwnDecisionRules();

        Boolean renewable = own && type.getTrialRenewable() != null ? type.getTrialRenewable()
            : config != null ? config.getTrialPeriodRenewable() : null;
        Integer max = own && type.getTrialMaxRenewals() != null ? type.getTrialMaxRenewals()
            : config != null ? config.getTrialMaxRenewals() : null;

        List<String> trialChoices = own ? parseChoices(type.getTrialDecisions()) : null;
        List<String> endChoices   = own ? parseChoices(type.getEndDecisions()) : null;

        return new ContractRules(Boolean.TRUE.equals(renewable), max != null ? max : 1,
            EmployeeLifecycleService.decisionsOf(config, nature, ContractDecisionAction.Moment.TRIAL_END),
            EmployeeLifecycleService.decisionsOf(config, nature, ContractDecisionAction.Moment.CONTRACT_END),
            trialChoices, endChoices, !own);
    }

    /** A stored CSV of choice codes → the codes, in order; null stays null (= not set). */
    public static List<String> parseChoices(String csv) {
        if (csv == null) return null;
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    }

    /**
     * Whether a CONTRACT_DECISION choice is offered at its action's moment.
     *
     * <p>Own choices are matched by code. A type saved before choices existed stored action
     * codes (« BREAK_TRIAL »): such a code still offers every choice of that action, unless a
     * choice is itself coded so — the built-in choices are, so they keep working unchanged.
     *
     * @param all every CONTRACT_DECISION choice of the contract's pays (to tell codes apart)
     */
    public boolean offers(ConfigurableListValue choice, ContractDecisionAction action,
                          List<ConfigurableListValue> all) {
        ContractDecisionAction.Moment moment = action.moment();
        List<String> choices = moment == ContractDecisionAction.Moment.TRIAL_END ? trialChoices : endChoices;
        if (choices == null) {
            return (moment == ContractDecisionAction.Moment.TRIAL_END ? trialDecisions : endDecisions).contains(action);
        }
        if (choices.contains(choice.getValueCode())) return true;
        return choices.contains(action.name())
            && all.stream().noneMatch(v -> Objects.equals(v.getValueCode(), action.name()));
    }
}
