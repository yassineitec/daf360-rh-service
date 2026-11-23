package com.daf360.rh.dto.hiring;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReviewCostApprovalRequest {

    @Size(max = 1000)
    private String notes;

    /** Counter-proposal salary (overrides candidates.salaire_net_rh when reject is submitted). */
    @DecimalMin(value = "0", inclusive = false, message = "Le salaire contre proposition doit être positif.")
    private BigDecimal contrePropSalaire;

    /**
     * The payroll engine's costing of {@code contrePropSalaire}, run in the DAF's decision
     * modal. On an offer round the counter-proposal becomes the next round, sent straight to
     * the candidate — and a round is never stored uncosted, so it is required then.
     */
    private String contrePropSimulationSnapshot;
}
