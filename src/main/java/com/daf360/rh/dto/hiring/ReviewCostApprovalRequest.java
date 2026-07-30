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
}
