package com.daf360.rh.dto.offer;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Payload to extend (send) a job offer to an ACCEPTED candidate. */
@Data
public class CreateOfferRequest {

    /** Net salary the candidate asked for (negotiation input). */
    private BigDecimal askedSalary;

    /** Net salary RH proposes. */
    private BigDecimal proposedSalary;

    @Size(max = 255)
    private String salaryNote;

    private LocalDate expectedHireDate;

    /** Offer validity date — after this the offer may be treated as expired. */
    private LocalDate expiryDate;
}
