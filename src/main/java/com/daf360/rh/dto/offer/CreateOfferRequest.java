package com.daf360.rh.dto.offer;

import jakarta.validation.constraints.Min;
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

    /**
     * Préavis négocié in calendar days. Absent on send → prefilled from the candidate's
     * grade default; absent on renegotiation → left as it was.
     */
    @Min(value = 0, message = "Le préavis ne peut pas être négatif.")
    private Integer noticePeriodDays;

    @Size(max = 255)
    private String noticePeriodNote;

    private LocalDate expectedHireDate;

    /** Offer validity date — after this the offer may be treated as expired. */
    private LocalDate expiryDate;
}
