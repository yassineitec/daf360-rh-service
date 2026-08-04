package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_checklist_items] (V60).
 *
 * One table, three lists, told apart by `groupCode`:
 *   HANDOVER — what the departing employee hands to their successor (stage 3)
 *   ACCESS   — accounts and accesses to revoke (stage 4)
 *   KIT      — the documents RH owes the employee (stage 5)
 *
 * Deliberately NOT `offboarding_task_catalog` rows: those carry an SLA, an owner role and
 * blocking semantics that none of these want, and they feed `computeProgress` — so
 * handing over a document would have counted as workflow progress.
 */
@Entity
@Table(name = "offboarding_checklist_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    /** HANDOVER | ACCESS | KIT — decides which stage renders it, and which permission edits it. */
    @Column(name = "group_code", nullable = false, length = 20)
    private String groupCode;

    /**
     * Stable per group and per instance (UX_checklist_item). ACCESS and KIT use the seeded
     * codes; HANDOVER items are created ad hoc and get a generated one.
     */
    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "item_label", nullable = false, length = 255,
            columnDefinition = "nvarchar(255)")
    private String itemLabel;

    @Column(name = "is_done", nullable = false)
    @Builder.Default
    private Boolean isDone = false;

    /** Proof attached to the line — a signed PV, an export, a screenshot. */
    @Column(name = "document_url", length = 500)
    private String documentUrl;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime completedAt;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
