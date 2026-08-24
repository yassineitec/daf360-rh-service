package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[mission_status_history] in DAF360_HR (V78) — who stamped what, and when.
 *
 * The mission row keeps only the LAST decision of each desk (hr_validated_by,
 * finance_decided_by…), so a mission that was rejected, re-planned and validated would
 * otherwise lose its first two steps. Same intent as offboarding_validators.
 *
 * Statuses are stored as plain strings: this is an append-only trail, and it must stay
 * readable even for a status code that a later release removes from the enum.
 */
@Entity
@Table(name = "mission_status_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mission_id", nullable = false)
    private Long missionId;

    /** Null for the very first entry (creation). */
    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String notes;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
