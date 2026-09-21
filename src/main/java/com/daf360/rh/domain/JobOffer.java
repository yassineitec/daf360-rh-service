package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.OfferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[job_offers] in DAF360_HR — one row per NEGOTIATION ROUND (V98).
 *
 * Captures the offer/negotiation stage of recruitment: the salary the candidate
 * asked for, the salary RH proposes, the validity window, and the candidate's
 * decision. Drives the "Offre" Kanban column.
 *
 * <p><b>It was one row per candidate until V98</b> (V41's UQ_JobOffer_Candidate), and
 * renegotiation overwrote it in place — so what round 2 offered survived only as an
 * audit-log line, and no round could carry its own budget approval. Each renegotiation
 * now INSERTS a round and stamps {@code supersededAt} on the one it replaces.
 *
 * <p>Reads must therefore say which round they mean. "The candidate's offer" is the round
 * with {@code supersededAt == null}; what a signed contract inherits is the ACCEPTED round,
 * which never moves again. See {@code JobOfferRepository}.
 *
 * CK_JobOffer_Status: DRAFT | SENT | ACCEPTED | REJECTED | EXPIRED
 * — DRAFT is costed and awaiting the finance decision, not yet with the candidate.
 */
@Entity
@Table(name = "job_offers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** No longer unique — one row per round since V98. */
    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    /** 1-based round of the negotiation. Shown to the user, so stored rather than derived. */
    @Column(name = "round_number", nullable = false)
    @Builder.Default
    private Integer roundNumber = 1;

    /** Set when a later round replaces this one. Null ⇒ this is the candidate's current offer. */
    @Column(name = "superseded_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime supersededAt;

    /** The round this one replaced — the negotiation chain, readable without arithmetic. */
    @Column(name = "supersedes_offer_id")
    private Long supersedesOfferId;

    /** Net salary the candidate asked for (negotiation input). */
    @Column(name = "asked_salary", precision = 12, scale = 3)
    private BigDecimal askedSalary;

    /** Net salary RH proposes in the offer. */
    @Column(name = "proposed_salary", precision = 12, scale = 3)
    private BigDecimal proposedSalary;

    /** Optional free-text (currency band, benefits, negotiation notes). */
    @Column(name = "salary_note", length = 255, columnDefinition = "nvarchar(255)")
    private String salaryNote;

    /**
     * Préavis négocié, in calendar days (V68) — prefilled from the candidate's grade default
     * and overridable, because this is the conversation where it is actually agreed.
     *
     * Null = not discussed. An input to the contract, which freezes its own copy: once
     * hired, nothing reads this again.
     */
    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    /** Why the négociation landed away from the grade default. */
    @Column(name = "notice_period_note", length = 255, columnDefinition = "nvarchar(255)")
    private String noticePeriodNote;

    /** Target start date proposed in the offer. */
    @Column(name = "expected_hire_date")
    private LocalDate expectedHireDate;

    /** Date the offer expires if the candidate does not decide (drives "Expire le …"). */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "sent_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime sentAt;

    @Column(name = "decided_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime decidedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfferStatus status = OfferStatus.SENT;

    @Column(name = "rejection_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String rejectionReason;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
