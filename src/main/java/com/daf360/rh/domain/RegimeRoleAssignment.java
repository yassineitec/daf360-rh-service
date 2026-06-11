package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "regime_role_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("is_active = 1")
public class RegimeRoleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "regime_id", nullable = false)
    private WorkingTimeRegime regime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    // pays — no Pays entity, store raw FK
    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // assignedBy — no User entity, store raw FK
    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "assigned_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime assignedAt;

    @Column(name = "notes", columnDefinition = "nvarchar(500)")
    private String notes;
}
