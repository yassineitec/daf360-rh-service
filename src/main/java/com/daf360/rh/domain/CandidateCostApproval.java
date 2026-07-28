package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "candidate_cost_approvals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateCostApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "salaire_net_rh", nullable = false, precision = 18, scale = 4)
    private BigDecimal salaireNetRh;

    @Column(name = "salaire_net_candidat", precision = 18, scale = 4)
    private BigDecimal salaireNetCandidat;

    @Column(name = "contract_type_code", nullable = false, length = 20)
    private String contractTypeCode;

    @Column(name = "simulation_snapshot", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String simulationSnapshot;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    @Column(name = "submitted_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime approvedAt;

    @Column(name = "approval_notes", length = 1000, columnDefinition = "NVARCHAR(1000)")
    private String approvalNotes;

    @PrePersist
    protected void prePersist() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }
}
