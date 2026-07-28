package com.daf360.rh.dto.hiring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SubmitCostApprovalRequest {

    @NotNull
    private Long candidateId;

    @NotNull
    private Long paysId;

    @NotNull
    @Positive
    private Integer fiscalYear;

    @NotNull
    @PositiveOrZero
    private BigDecimal salaireNetRh;

    @PositiveOrZero
    private BigDecimal salaireNetCandidat;

    @NotBlank
    private String contractTypeCode;

    /** JSON string of the full PayrollResult snapshot returned by the payroll engine. */
    @NotBlank
    private String simulationSnapshot;
}
