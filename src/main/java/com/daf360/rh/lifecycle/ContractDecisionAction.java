package com.daf360.rh.lifecycle;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The decisions the lifecycle engine knows how to carry out at a contract deadline.
 *
 * <p>The admin names them freely (list CONTRACT_DECISION, Administration › Listes
 * configurables — the action is stored in {@code decision_action})
 * and picks which ones each contract type offers (Administration ›
 * Échéances de contrat → {@code contract_type_config.trial_decisions / end_decisions}). What a
 * decision DOES is fixed here: an admin can rename or hide a choice, never invent a behaviour.
 */
public enum ContractDecisionAction {

    /** Trial passed → ACTIF. */
    CONFIRM_TRIAL(Moment.TRIAL_END, false),
    /** One more trial period → stays PERIODE_ESSAI, new end date. */
    RENEW_TRIAL(Moment.TRIAL_END, true),
    /** Trial not conclusive → RUPTURE_PE. */
    BREAK_TRIAL(Moment.TRIAL_END, false),
    /** CDD only, once — same rules as « Renouveler le CDD ». */
    RENEW_CDD(Moment.CONTRACT_END, true),
    /** New planned end date (CIVP, stage, mission, détachement), within the type's maximum. */
    EXTEND(Moment.CONTRACT_END, true),
    /** The contract ends → FIN_CONTRAT / FIN_STAGE / FIN_MISSION depending on the nature. */
    END_CONTRACT(Moment.CONTRACT_END, false);

    /** When a decision is taken: at the end of the trial, or at the planned end of the contract. */
    public enum Moment { TRIAL_END, CONTRACT_END }

    private final Moment  moment;
    private final boolean requiresDate;

    ContractDecisionAction(Moment moment, boolean requiresDate) {
        this.moment = moment;
        this.requiresDate = requiresDate;
    }

    public Moment moment()        { return moment; }
    public boolean requiresDate() { return requiresDate; }

    public static Optional<ContractDecisionAction> parse(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String code = s.trim().toUpperCase();
        return Arrays.stream(values()).filter(a -> a.name().equals(code)).findFirst();
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * What a contract nature offers when its contract_type_config row says nothing
     * (column NULL) — the behaviour before the choices became configurable.
     */
    public static List<ContractDecisionAction> defaults(String nature, Moment moment) {
        String n = nature == null ? "CDI" : nature;
        if (moment == Moment.TRIAL_END) {
            return "CDI".equals(n)
                ? List.of(CONFIRM_TRIAL, RENEW_TRIAL, BREAK_TRIAL)
                : List.of(CONFIRM_TRIAL, BREAK_TRIAL);
        }
        return switch (n) {
            case "CDI" -> List.of();
            case "CDD" -> List.of(RENEW_CDD, END_CONTRACT);
            default    -> List.of(EXTEND, END_CONTRACT);
        };
    }

    /** A stored CSV (« CONFIRM_TRIAL,BREAK_TRIAL ») → actions; unknown codes are dropped. */
    public static List<ContractDecisionAction> parseCsv(String csv) {
        if (csv == null) return null;
        return Arrays.stream(csv.split(","))
            .map(ContractDecisionAction::parse)
            .flatMap(Optional::stream)
            .distinct()
            .toList();
    }
}
