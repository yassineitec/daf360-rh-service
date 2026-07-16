package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps [dbo].[offboarding_workflow_instances].
 * One row per offboarding process per employee.
 * Created in V38 migration.
 */
@Entity
@Table(name = "offboarding_workflow_instances")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingWorkflowInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "trigger_date", nullable = false)
    private LocalDate triggerDate;

    @Column(name = "last_working_day")
    private LocalDate lastWorkingDay;

    /** RESIGNATION | FIN_CONTRAT | LICENCIEMENT | RETRAITE | FIN_STAGE | FIN_MISSION | AUTRE */
    @Column(name = "departure_reason", nullable = false, length = 100,
            columnDefinition = "nvarchar(100)")
    private String departureReason;

    @Column(name = "departure_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String departureNotes;

    /** PENDING | IN_PROGRESS | BLOCKED | VALIDATED | CANCELLED | ARCHIVED */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "initiated_by", nullable = false)
    private Long initiatedBy;

    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "validated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime validatedAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String cancellationReason;

    @Column(name = "sla_breach_flag", nullable = false)
    @Builder.Default
    private Boolean slaBreachFlag = false;

    @Column(name = "completion_date", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime completionDate;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "handover_manager_profile_id")
    private Long handoverManagerProfileId;

    @OneToMany(mappedBy = "workflowInstance", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<OffboardingTask> tasks = new ArrayList<>();
}
