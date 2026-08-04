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

    // ── Stage 1 — Déclaration (V57) ──────────────────────────────────────────
    // Filled after creation: a file started from a profile carries only a departure
    // type, and stays in the Déclaration stage until these are set.

    /** Resignation letter / termination notice, uploaded through the profile's documents. */
    @Column(name = "justification_document_url", length = 500, columnDefinition = "nvarchar(500)")
    private String justificationDocumentUrl;

    @Column(name = "justification_document_name", length = 255, columnDefinition = "nvarchar(255)")
    private String justificationDocumentName;

    /** "3 mois", "1 mois", "Aucun" — per the pays' convention collective, not arithmetic. */
    @Column(name = "notice_period_label", length = 50, columnDefinition = "nvarchar(50)")
    private String noticePeriodLabel;

    @Column(name = "notice_waiver_requested", nullable = false)
    @Builder.Default
    private Boolean noticeWaiverRequested = false;

    /** Trigger date + notice per the convention. `lastWorkingDay` is what was agreed. */
    @Column(name = "theoretical_exit_date")
    private LocalDate theoreticalExitDate;

    // ── Stage 3 — Passation (V60) ────────────────────────────────────────────

    /** PV de passation — the signed record that the handover happened. */
    @Column(name = "handover_minutes_url", length = 500, columnDefinition = "nvarchar(500)")
    private String handoverMinutesUrl;

    @Column(name = "handover_minutes_name", length = 255, columnDefinition = "nvarchar(255)")
    private String handoverMinutesName;

    // ── Stage 6 — Solde de tout compte (V63) ─────────────────────────────────

    /** When the settlement is actually paid. The amounts live in settlement_lines. */
    @Column(name = "settlement_execution_date")
    private LocalDate settlementExecutionDate;

    // ── Stage 4 — Informatique & Matériel (V61) ──────────────────────────────

    /** When the accounts were (or will be) switched off. A moment, not a day. */
    @Column(name = "account_deactivation_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime accountDeactivationAt;

    /** Décharge de matériel — what the employee signs to certify the returns. */
    @Column(name = "discharge_document_url", length = 500, columnDefinition = "nvarchar(500)")
    private String dischargeDocumentUrl;

    @Column(name = "discharge_document_name", length = 255, columnDefinition = "nvarchar(255)")
    private String dischargeDocumentName;

    // ── Stage 2 — Validation Manager & RH (V59) ──────────────────────────────
    // Distinct from validated_by/at below, which is the FILE-level closure (stage 7).
    // Overloading that one is what made stage 2 turn green when the file was closed.

    @Column(name = "manager_validated_by")
    private Long managerValidatedBy;

    @Column(name = "manager_validated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime managerValidatedAt;

    @Column(name = "manager_comment", length = 1000, columnDefinition = "nvarchar(1000)")
    private String managerComment;

    @Column(name = "hr_validated_by")
    private Long hrValidatedBy;

    @Column(name = "hr_validated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime hrValidatedAt;

    /** Notice paid but not served — feeds the solde de tout compte. */
    @Column(name = "notice_paid_not_worked", nullable = false)
    @Builder.Default
    private Boolean noticePaidNotWorked = false;

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
