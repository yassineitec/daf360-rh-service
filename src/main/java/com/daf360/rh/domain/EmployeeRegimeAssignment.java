package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A personal regime for one employee over a date window (V127, employee_regime_assignments).
 *
 * Replaces the single slot of employee_profiles.regime_template_id: an employee may have
 * several rows — a permanent part-time regime plus a business trip on top of it, or a
 * change scheduled for next month — and the resolver takes, for any date, the newest row
 * covering it (latest effective_from, then highest id).
 *
 * is_active = false means CANCELLED (it never applied). An assignment that has ENDED stays
 * active with an effective_to in the past, so "which regime applied on that date" keeps
 * an answer.
 */
@Entity
@Table(name = "employee_regime_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeRegimeAssignment {

    public static final String SOURCE_MANUAL     = "MANUAL";
    public static final String SOURCE_ONBOARDING = "ONBOARDING";
    public static final String SOURCE_MIGRATION  = "MIGRATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "regime_id", nullable = false)
    private WorkingTimeRegime regime;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "reason", columnDefinition = "nvarchar(500)")
    private String reason;

    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = SOURCE_MANUAL;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime assignedAt;

    @Column(name = "ended_by")
    private Long endedBy;

    @Column(name = "ended_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime endedAt;

    /** Inclusive window test; a null end is open. */
    public boolean covers(LocalDate date) {
        return Boolean.TRUE.equals(isActive)
                && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
