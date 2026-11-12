package com.daf360.rh.lists;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ListValueResponse {

    private Long           id;
    private Long           listTypeId;
    private Long           paysId;
    private String         valueCode;
    private String         labelFr;
    private String         labelEn;
    private Integer        sortOrder;
    private Boolean        isActive;
    private Boolean        isSystem;
    private String         payrollContractCode;
    /** CONTRACT_TYPE only: CDI, CDD, CIVP, STAGE, FREELANCE or DETACHEMENT — see ContractTypeRefs. */
    private String         lifecycleNature;
    /** CONTRACT_DECISION only: CONFIRM_TRIAL, RENEW_TRIAL… — see ContractDecisionAction. */
    private String         decisionAction;
    /** CONTRACT_TYPE only: true when the type has its own « Décider » rules (else it inherits its nature's). */
    private Boolean        customDecisions;
    private Boolean        trialRenewable;
    private Integer        trialMaxRenewals;
    /** Own choices, as stored — null when inherited. */
    private java.util.List<String> trialDecisions;
    private java.util.List<String> endDecisions;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
