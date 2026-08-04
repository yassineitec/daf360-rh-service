package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_settlement_lines] (V63) — one line of a solde de tout compte.
 *
 * Stored rather than computed because two of the three usual lines have no source in
 * rh-service: there is no leave-balance table (congés payés) and no per-pays convention scale
 * (indemnité de rupture). Only the prorata 13ᵉ mois is derivable, from
 * `employee_profiles.salaire_net_rh` and `hire_date` — it is offered as a suggestion and
 * flagged with `isSuggested` so an audit can tell a proposed figure from a typed one.
 */
@Entity
@Table(name = "offboarding_settlement_lines")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingSettlementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    @Column(name = "label", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String label;

    /** Signed: a STC carries deductions as well as payments. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    /** True while the figure is the one the system proposed and nobody has overridden. */
    @Column(name = "is_suggested", nullable = false)
    @Builder.Default
    private Boolean isSuggested = false;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
