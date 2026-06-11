package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps [leave_balances] in DAF360_HR.
 * Schema verified 2026-05-31: id, employee_profile_id, annee, leave_type,
 * jours_acquis numeric(6,2) NOT NULL, jours_pris numeric(6,2) NOT NULL,
 * jours_restants numeric(7,2) NULLABLE, derniere_maj datetimeoffset NOT NULL.
 *
 * FK_LeaveBalance_Profile: employee_profile_id → employee_profiles.id
 */
@Entity
@Table(name = "leave_balances",
       uniqueConstraints = @UniqueConstraint(
               name = "UQ_leave_balance_profile_year_type",
               columnNames = {"employee_profile_id", "annee", "leave_type"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "annee", nullable = false)
    private Integer annee;

    @Column(name = "leave_type", nullable = false, length = 50)
    private String leaveType;

    @Column(name = "jours_acquis", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal joursAcquis = BigDecimal.ZERO;

    @Column(name = "jours_pris", nullable = false, precision = 6, scale = 2)
    @Builder.Default
    private BigDecimal joursPris = BigDecimal.ZERO;

    /** Computed: joursAcquis - joursPris. Nullable — updated on every change. */
    @Column(name = "jours_restants", precision = 7, scale = 2)
    private BigDecimal joursRestants;

    @Column(name = "derniere_maj", nullable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime derniereMaj;
}
