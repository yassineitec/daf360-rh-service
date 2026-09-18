package com.daf360.rh.dto.offer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to draft an offer ROUND — the first one, or a renegotiation superseding the
 * current one (V98).
 *
 * <p>It no longer sends anything to the candidate. A round is created DRAFT and goes to the
 * finance approval queue; {@code POST /{id}/offer/send} is what extends an approved round.
 * That is why the simulation fields below are part of this payload: a round is costed at the
 * moment it is drafted, so an offer can never reach the queue without the employer cost that
 * justifies it.
 */
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

    // ── Cost simulation for this round (V98) ─────────────────────────────────

    /**
     * The payroll engine's answer for {@code proposedSalary}, as returned by
     * {@code POST /api/payroll/simulations/individual}, stored verbatim.
     *
     * <p>Kept as raw JSON rather than parsed into columns, exactly as the existing
     * {@code candidate_cost_approvals.simulation_snapshot} does: it is EVIDENCE of what the
     * engine said on the day, and re-deriving it later against changed payroll parameters
     * would answer a different question from the one finance approved.
     *
     * <p>Absent means no round is created — the offer cannot be drafted uncosted.
     */
    private String simulationSnapshot;

    /** Budget year the cost lands in. Absent → the current year. */
    private Integer fiscalYear;
}
