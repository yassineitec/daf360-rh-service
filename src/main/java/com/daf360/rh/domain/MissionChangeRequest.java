package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.MissionChangeRequestStatus;
import com.daf360.rh.domain.enums.MissionChangeRequestType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[mission_change_requests] in DAF360_HR (V78) — what the employee asks for on
 * their own, already validated, mission.
 *
 * RH resolves them: accepting a PERIOD_CHANGE moves the mission's dates, accepting a
 * CANCELLATION cancels the mission. The mission row is never touched by the employee
 * directly — this table is the only way in, so every change keeps a reason and an author.
 */
@Entity
@Table(name = "mission_change_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mission_id", nullable = false)
    private Long missionId;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private MissionChangeRequestType requestType;

    /** Both null for a CANCELLATION; both required for a PERIOD_CHANGE. */
    @Column(name = "requested_start_date")
    private LocalDate requestedStartDate;

    @Column(name = "requested_end_date")
    private LocalDate requestedEndDate;

    @Column(name = "reason", nullable = false, length = 1000, columnDefinition = "nvarchar(1000)")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MissionChangeRequestStatus status = MissionChangeRequestStatus.PENDING;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null)    status    = MissionChangeRequestStatus.PENDING;
    }
}
