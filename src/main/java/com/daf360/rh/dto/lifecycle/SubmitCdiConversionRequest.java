package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Files a CIVP → CDI conversion request on one contract. */
@Data
public class SubmitCdiConversionRequest {

    /** Desired first day of the CDI. */
    @NotNull
    private LocalDate requestedStartDate;

    /** Net monthly salary proposed for the CDI. */
    @NotNull
    @DecimalMin(value = "0.001")
    private BigDecimal proposedNetSalary;

    /** Technical justification: the resource's assignments, the managers' feedback. */
    @NotBlank
    @Size(max = 2000)
    private String justification;
}
