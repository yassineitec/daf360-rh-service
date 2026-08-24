package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[mission_expenses] in DAF360_HR (V78) — the billeterie sheet, one row per
 * mission, filled by RH.
 *
 * {@code totalEstimatedCost} is the number finance decides on. It is ALWAYS recomputed
 * from the amounts below ({@link #recomputeTotal()}); a client-sent total is ignored,
 * otherwise the figure finance approves could differ from the lines that justify it.
 *
 * {@code transportMode} and {@code paymentMethod} stay Strings rather than enums: they are
 * open lists guarded by a CHECK constraint, and RH adds to them from the database, not from
 * a release.
 */
@Entity
@Table(name = "mission_expenses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mission_id", nullable = false, unique = true)
    private Long missionId;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "TND";

    /** Informative day rate; {@code missionAllowance} is the amount that enters the total. */
    @Column(name = "allowance_daily_rate", precision = 18, scale = 4)
    private BigDecimal allowanceDailyRate;

    /** Frais de mission (per diem total). */
    @Column(name = "mission_allowance", precision = 18, scale = 4)
    private BigDecimal missionAllowance;

    /** Coût de logement. */
    @Column(name = "lodging_cost", precision = 18, scale = 4)
    private BigDecimal lodgingCost;

    @Column(name = "hotel_name", length = 255, columnDefinition = "nvarchar(255)")
    private String hotelName;

    @Column(name = "nights")
    private Integer nights;

    /** Numéro de réservation (hôtel / agence). */
    @Column(name = "reservation_number", length = 100, columnDefinition = "nvarchar(100)")
    private String reservationNumber;

    /** AVION | TRAIN | BUS | VOITURE | BATEAU | AUTRE — CK_MissionExpenses_Transport. */
    @Column(name = "transport_mode", length = 20)
    private String transportMode;

    @Column(name = "transport_carrier", length = 120, columnDefinition = "nvarchar(120)")
    private String transportCarrier;

    /** Billet de transport — the reference printed on the ticket. */
    @Column(name = "ticket_reference", length = 100, columnDefinition = "nvarchar(100)")
    private String ticketReference;

    @Column(name = "ticket_cost", precision = 18, scale = 4)
    private BigDecimal ticketCost;

    /** The only hours in the whole process — hence an offset datetime and not a date. */
    @Column(name = "outbound_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime outboundAt;

    @Column(name = "return_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime returnAt;

    /** RESERVED: scanned ticket / réservation. No upload UI yet. */
    @Column(name = "ticket_document_url", length = 500, columnDefinition = "nvarchar(500)")
    private String ticketDocumentUrl;

    @Column(name = "visa_fees", precision = 18, scale = 4)
    private BigDecimal visaFees;

    @Column(name = "insurance_fees", precision = 18, scale = 4)
    private BigDecimal insuranceFees;

    @Column(name = "other_fees", precision = 18, scale = 4)
    private BigDecimal otherFees;

    @Column(name = "other_fees_label", length = 255, columnDefinition = "nvarchar(255)")
    private String otherFeesLabel;

    /**
     * Avance handed to the employee. NOT part of the total: it is a share of it paid up
     * front, so adding it would count the same money twice.
     */
    @Column(name = "advance_amount", precision = 18, scale = 4)
    private BigDecimal advanceAmount;

    /** ESPECES | VIREMENT | CARTE | AUTRE — CK_MissionExpenses_Payment. */
    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    /** Date de récupération de l'argent. */
    @Column(name = "cash_pickup_date")
    private LocalDate cashPickupDate;

    /** Date de récupération des documents (billets, visa, passeport…). */
    @Column(name = "document_pickup_date")
    private LocalDate documentPickupDate;

    @Column(name = "total_estimated_cost", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalEstimatedCost = BigDecimal.ZERO;

    @Column(name = "hr_notes", length = 2000, columnDefinition = "nvarchar(2000)")
    private String hrNotes;

    @Column(name = "prepared_by")
    private Long preparedBy;

    @Column(name = "prepared_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime preparedAt;

    @Column(name = "updated_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime updatedAt;

    /**
     * Sums every cost line into {@code totalEstimatedCost}. Called on every write, never
     * driven by the client.
     *
     * {@code advanceAmount} is excluded on purpose — see its javadoc.
     */
    public void recomputeTotal() {
        totalEstimatedCost = sum(missionAllowance, lodgingCost, ticketCost,
                                 visaFees, insuranceFees, otherFees);
    }

    private static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v != null) total = total.add(v);
        }
        return total;
    }

    @PrePersist
    protected void prePersist() {
        if (totalEstimatedCost == null) recomputeTotal();
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
