package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[employee_lifecycle_transitions] — append-only audit trail.
 * D3-104: NEVER UPDATE OR DELETE rows from this table.
 * Every state transition, including contract creation, is logged here.
 */
@Entity
@Table(name = "employee_lifecycle_transitions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeLifecycleTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private EmployeeContract contract;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "statut_avant", length = 50)
    private String statutAvant;

    @Column(name = "statut_apres", nullable = false, length = 50)
    private String statutApres;

    @Column(name = "action_code", nullable = false, length = 50)
    private String actionCode;

    @Column(name = "triggered_by_user_id", nullable = false)
    private Long triggeredByUserId;

    @Column(name = "triggered_at", nullable = false, columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime triggeredAt = OffsetDateTime.now();

    @Column(name = "commentaire", length = 500, columnDefinition = "nvarchar(500)")
    private String commentaire;

    @Column(name = "document_reference", length = 255, columnDefinition = "nvarchar(255)")
    private String documentReference;

    @Column(name = "metadata", length = 1000, columnDefinition = "nvarchar(1000)")
    private String metadata;
}
