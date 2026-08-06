package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_validators] (V66) — the role allowed to give the RH validation of an
 * offboarding, for one pays.
 *
 * Exists because `RH_VALIDATE_OFFBOARDING` cannot express a country: a permission belongs to a
 * role and a role has no pays. The role itself is chosen from `Roles` rather than named in code,
 * so a deployment that calls its country director something else still works.
 *
 * A pays with no row is unrestricted — see V66 for why that fallback is deliberate.
 */
@Entity
@Table(name = "offboarding_validators")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingValidator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique — one validator role per country. */
    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
