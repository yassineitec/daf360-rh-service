package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdjustBalanceDto {

    /** Positive to add days, negative to deduct. */
    @NotNull(message = "Le delta est obligatoire")
    private BigDecimal delta;

    @NotBlank(message = "La raison est obligatoire")
    @Size(max = 500)
    private String reason;
}
