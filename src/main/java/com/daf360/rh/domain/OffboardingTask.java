package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_tasks].
 * One row per task within an offboarding workflow instance.
 * Created in V38 migration.
 */
@Entity
@Table(name = "offboarding_tasks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_instance_id", nullable = false)
    private OffboardingWorkflowInstance workflowInstance;

    @Column(name = "task_code", nullable = false, length = 50)
    private String taskCode;

    @Column(name = "task_label", nullable = false, length = 255,
            columnDefinition = "nvarchar(255)")
    private String taskLabel;

    @Column(name = "owner_role", nullable = false, length = 100,
            columnDefinition = "nvarchar(100)")
    private String ownerRole;

    /** Raw FK to Users.id — no User entity in rh-service */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private Boolean isMandatory = true;

    @Column(name = "is_blocking", nullable = false)
    @Builder.Default
    private Boolean isBlocking = false;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** PENDING | IN_PROGRESS | DONE | BLOCKED | SKIPPED */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime completedAt;

    @Column(name = "skipped_by")
    private Long skippedBy;

    @Column(name = "skip_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String skipReason;

    @Column(name = "comments", length = 2000, columnDefinition = "nvarchar(2000)")
    private String comments;

    @Column(name = "attached_document_url", length = 500, columnDefinition = "nvarchar(500)")
    private String attachedDocumentUrl;

    @Column(name = "sla_breach_date", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime slaBreachDate;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
