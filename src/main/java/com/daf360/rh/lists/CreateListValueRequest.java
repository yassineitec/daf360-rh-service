package com.daf360.rh.lists;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateListValueRequest {

    @NotNull
    private Long listTypeId;

    /** Nullable — value applies to all countries when absent */
    private Long paysId;

    @NotBlank
    @Size(max = 100)
    private String valueCode;

    @NotBlank
    @Size(max = 255)
    private String labelFr;

    @NotBlank
    @Size(max = 255)
    private String labelEn;

    private Integer sortOrder;

    /** CONTRACT_TYPE only: the lifecycle rules this type follows. Absent → CDI. */
    @Size(max = 30)
    private String lifecycleNature;

    /** CONTRACT_DECISION only: the action the choice carries out. Absent → the code's own. */
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
