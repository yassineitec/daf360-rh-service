package com.daf360.rh.lifecycle;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Defines allowed state transitions per contract type.
 * D3-96: each contract type has its own state machine.
 * Used by EmployeeLifecycleService.transitionState() to validate requested transitions.
 */
@Component
public class LifecycleStateMachine {

    public List<String> getAllowedTransitions(String contractType, String currentStatus) {
        return switch (contractType) {

            case "CDI" -> switch (currentStatus) {
                case "RECRUTEMENT"   -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI" -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"         -> List.of("ACTIF", "SUSPENDU", "FIN_CONTRAT", "RETRAITE");
                case "SUSPENDU"      -> List.of("ACTIF", "FIN_CONTRAT");
                case "FIN_CONTRAT"   -> List.of("INACTIF");
                case "RETRAITE"      -> List.of("INACTIF");
                default              -> List.of();
            };

            case "CDD" -> switch (currentStatus) {
                case "RECRUTEMENT"        -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI"      -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"              -> List.of("RENOUVELLEMENT_CDD", "CONVERSION_CDI", "FIN_CONTRAT");
                case "RENOUVELLEMENT_CDD" -> List.of("ACTIF", "FIN_CONTRAT");
                case "CONVERSION_CDI"     -> List.of("ACTIF");
                case "FIN_CONTRAT"        -> List.of("INACTIF");
                default                   -> List.of();
            };

            case "CIVP" -> switch (currentStatus) {
                case "RECRUTEMENT"   -> List.of("PERIODE_ESSAI");
                case "PERIODE_ESSAI" -> List.of("ACTIF", "RUPTURE_PE");
                case "ACTIF"         -> List.of("CONVERSION_CDI", "FIN_CONTRAT");
                case "FIN_CONTRAT"   -> List.of("INACTIF");
                default              -> List.of();
            };

            case "STAGE" -> switch (currentStatus) {
                case "RECRUTEMENT"       -> List.of("CONVENTION_SIGNEE");
                case "CONVENTION_SIGNEE" -> List.of("STAGE_ACTIF");
                case "STAGE_ACTIF"       -> List.of("FIN_STAGE", "FIN_CONTRAT");
                case "FIN_STAGE"         -> List.of("INACTIF");
                default                  -> List.of();
            };

            case "FREELANCE" -> switch (currentStatus) {
                case "SOURCING_PRESTATAIRE" -> List.of("MISSION_ACTIVE");
                case "MISSION_ACTIVE"       -> List.of("MISSION_ACTIVE", "FIN_MISSION", "RESILIATION");
                case "FIN_MISSION"          -> List.of("MISSION_ACTIVE", "INACTIF");
                case "RESILIATION"          -> List.of("INACTIF");
                default                     -> List.of();
            };

            case "DETACHEMENT" -> switch (currentStatus) {
                case "ACTIF"                  -> List.of("ACCORD_DETACHEMENT");
                case "ACCORD_DETACHEMENT"     -> List.of("DETACHEMENT_ACTIF");
                case "DETACHEMENT_ACTIF"      -> List.of("DETACHEMENT_ACTIF", "RETOUR_ENTITE_A",
                                                          "INTEGRATION_DEFINITIVE", "FIN_CONTRAT");
                case "RETOUR_ENTITE_A"        -> List.of("ACTIF");
                case "INTEGRATION_DEFINITIVE" -> List.of("INACTIF");
                default                       -> List.of();
            };

            default -> List.of();
        };
    }

    public boolean isTransitionAllowed(String contractType, String fromStatus, String toStatus) {
        return getAllowedTransitions(contractType, fromStatus).contains(toStatus);
    }
}
