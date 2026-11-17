package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** POST /api/hr/contracts/{id}/decide — one choice of the CONTRACT_DECISION list. */
@Data
public class ContractDecisionRequest {

    /** configurable_list_values.id of the chosen CONTRACT_DECISION value. */
    @NotNull
    private Long decisionId;

    /** New end date — required by RENEW_TRIAL, RENEW_CDD and EXTEND, ignored otherwise. */
    private LocalDate newDate;

    @Size(max = 1000)
    private String commentaire;
}
