package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.OfferStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[job_offers] in DAF360_HR (V41) — one offer per candidate.
 *
 * Captures the offer/negotiation stage of recruitment: the salary the candidate
 * asked for, the salary RH proposes, the validity window, and the candidate's
 * decision. Drives the "Offre" Kanban column.
 *
 * CK_JobOffer_Status: SENT | ACCEPTED | REJECTED | EXPIRED
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

    @Column(name = "candidate_id", nullable = false, unique = true)
    private Long candidateId;

    /** Net salary the candidate asked for (negotiation input). */
    @Column(name = "asked_salary", precision = 12, scale = 3)
    private BigDecimal askedSalary;

    /** Net salary RH proposes in the offer. */
    @Column(name = "proposed_salary", precision = 12, scale = 3)
    private BigDecimal proposedSalary;

    /** Optional free-text (currency band, benefits, negotiation notes). */
    @Column(name = "salary_note", length = 255, columnDefinition = "nvarchar(255)")
    private String salaryNote;

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
