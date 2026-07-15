package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[exit_interviews].
 * One exit interview per offboarding workflow instance (UNIQUE constraint).
 * Created in V38 migration.
 */
@Entity
@Table(name = "exit_interviews")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExitInterview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_instance_id", nullable = false)
    private OffboardingWorkflowInstance workflowInstance;

    /** Raw FK to Users.id */
    @Column(name = "conducted_by", nullable = false)
    private Long conductedBy;

    @Column(name = "conducted_date", nullable = false)
    private LocalDate conductedDate;

    /** JSON array of departure reason codes */
    @Column(name = "departure_reasons", length = 1000, columnDefinition = "nvarchar(1000)")
    private String departureReasons;

    @Column(name = "feedback_text", length = 4000, columnDefinition = "nvarchar(4000)")
    private String feedbackText;

    @Column(name = "is_anonymised", nullable = false)
    @Builder.Default
    private Boolean isAnonymised = false;

    @Column(name = "anonymised_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime anonymisedAt;

    /** Comma-separated role codes allowed to read the feedback */
    @Column(name = "visible_to_roles", length = 500, columnDefinition = "nvarchar(500)")
    private String visibleToRoles;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
