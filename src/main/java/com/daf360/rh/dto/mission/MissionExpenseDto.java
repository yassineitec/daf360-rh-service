package com.daf360.rh.dto.mission;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The billeterie sheet, both ways: RH sends it on PUT /expenses, everyone reads it back on
 * the mission.
 *
 * {@code totalEstimatedCost}, {@code preparedBy} and {@code preparedAt} are READ-ONLY —
 * the service recomputes the total from the lines and stamps the author itself. A total
 * sent by a client is ignored, so the figure finance approves can never disagree with the
 * lines that justify it.
 */
@Data
public class MissionExpenseDto {

    private String currency;

    private BigDecimal allowanceDailyRate;
    private BigDecimal missionAllowance;

    private BigDecimal lodgingCost;
    private String     hotelName;
    private Integer    nights;
    private String     reservationNumber;

    private String         transportMode;
    private String         transportCarrier;
    private String         ticketReference;
    private BigDecimal     ticketCost;
    private OffsetDateTime outboundAt;
    private OffsetDateTime returnAt;

    private BigDecimal visaFees;
    private BigDecimal insuranceFees;
    private BigDecimal otherFees;
    private String     otherFeesLabel;

    private BigDecimal advanceAmount;
    private String     paymentMethod;

    private LocalDate cashPickupDate;
    private LocalDate documentPickupDate;

    private String hrNotes;

    /** Read-only — recomputed server-side on every write. */
    private BigDecimal     totalEstimatedCost;
    /** Read-only. */
    private Long           preparedBy;
    /** Read-only. */
    private OffsetDateTime preparedAt;
}
