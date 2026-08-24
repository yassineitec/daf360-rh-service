package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.MissionScope;
import com.daf360.rh.domain.enums.MissionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[missions] in DAF360_HR (V78) — one ordre de mission.
 *
 * The row travels through three desks: the manager who plans it, RH who prices it in the
 * billeterie screen, and finance who takes the final decision. Each desk writes its own
 * pair of columns and never touches the others'; the full trail is in
 * {@link MissionStatusHistory}.
 *
 * Everyone is a {@code Users.id}, not an {@code employee_profile_id}: the "who may plan a
 * mission for whom" rule is the role hierarchy (Users.role_id → Roles.parent_role_id), and
 * the self-service / calendar screens only ever know the caller's user id.
 */
@Entity
@Table(name = "missions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pays of the employee sent on the mission, copied at creation so the queues can filter. */
    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "employee_user_id", nullable = false)
    private Long employeeUserId;

    /** The manager who planned it. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * Responsable of the mission — a colleague ({@code responsableUserId}) or an external
     * contact ({@code responsableName}). The service requires at least one of the two;
     * neither is enough on its own to be made NOT NULL in the schema.
     */
    @Column(name = "responsable_user_id")
    private Long responsableUserId;

    @Column(name = "responsable_name", length = 255, columnDefinition = "nvarchar(255)")
    private String responsableName;

    /** Subject line. The calendar and the self-service card print this; `details` is the long form. */
    @Column(name = "title", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String title;

    @Column(name = "details", columnDefinition = "NVARCHAR(MAX)")
    private String details;

    /**
     * Whole days, in the mission's own country — deliberately not DATETIMEOFFSET. The only
     * hours that matter are the travel times, and those live on {@link MissionExpense}.
     */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private MissionScope scope;

    /** Set when the destination country is one of ours; {@code countryLabel} otherwise. */
    @Column(name = "destination_pays_id")
    private Long destinationPaysId;

    @Column(name = "country_label", length = 120, columnDefinition = "nvarchar(120)")
    private String countryLabel;

    @Column(name = "city", nullable = false, length = 120, columnDefinition = "nvarchar(120)")
    private String city;

    @Column(name = "address", length = 500, columnDefinition = "nvarchar(500)")
    private String address;

    /**
     * RESERVED, deliberately unread for now: the day a mission has to be charged to a
     * project, this is the link finance will allocate on. Kept on the entity so the column
     * is not orphaned, never set by any code path yet.
     */
    @Column(name = "affaire_id")
    private Long affaireId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private MissionStatus status = MissionStatus.PENDING_HR;

    @Column(name = "hr_validated_by")
    private Long hrValidatedBy;

    @Column(name = "hr_validated_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime hrValidatedAt;

    @Column(name = "hr_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String hrNotes;

    @Column(name = "finance_decided_by")
    private Long financeDecidedBy;

    @Column(name = "finance_decided_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime financeDecidedAt;

    @Column(name = "finance_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String financeNotes;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancelled_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String cancellationReason;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null)    status    = MissionStatus.PENDING_HR;
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
