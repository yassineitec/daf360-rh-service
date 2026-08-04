package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Adds or edits a settlement line.
 *
 * No bound on the amount and negatives allowed: a STC carries deductions (avance sur salaire,
 * matériel non restitué) as well as payments, and forcing positives would push the sign into
 * the label where nothing can total it.
 */
@Data
public class SaveSettlementLineDto {

    @NotBlank
    @Size(max = 255)
    private String label;

    @NotNull
    private BigDecimal amount;
}
