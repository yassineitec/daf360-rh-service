package com.daf360.rh.lists;

import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * PATCH payload — every field is optional.
 * Absent fields must be treated as "no change" in the service layer.
 *
 * NOTE: Must NOT use @Builder — Jackson 3.x requires a no-args constructor
 * to deserialize @RequestBody, and @Builder suppresses it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateListValueRequest {

    @Size(max = 255)
    private String labelFr;

    @Size(max = 255)
    private String labelEn;

    private Integer sortOrder;

    private Boolean isActive;

    /** When {@code true} the service bypasses the is-system guard on deactivation. */
    private Boolean forceDeactivate;   // null = false (service uses Boolean.TRUE.equals check)

    @Size(max = 20)
    private String payrollContractCode;

    /** CONTRACT_TYPE only: the lifecycle rules this type follows (CDI, CDD, CIVP, STAGE, FREELANCE, DETACHEMENT). */
    @Size(max = 30)
    private String lifecycleNature;

    /** CONTRACT_DECISION only: the action the choice carries out (ContractDecisionAction). */
    @Size(max = 30)
    private String decisionAction;

    /*
     * CONTRACT_TYPE only — this type's own « Décider » rules. customDecisions: true sets the
     * four fields below, false clears them (the type inherits its nature's rules), absent = no change.
     */
    private Boolean customDecisions;
    private Boolean trialRenewable;
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(10)
    private Integer trialMaxRenewals;
    private java.util.List<String> trialDecisions;
    private java.util.List<String> endDecisions;
}
