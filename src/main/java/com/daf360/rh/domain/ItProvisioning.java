package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.ItProvisioningStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[it_provisioning] in DAF360_HR.
 * Schema verified 2026-06-01 (27 columns).
 * FK_ITProv_Candidate → candidates, FK_ITProv_User / CompletedBy → Users.
 * CK_ITProv_Status: PENDING | IN_PROGRESS | EMAIL_CREATED | COMPLETED
 */
@Entity
@Table(name = "it_provisioning")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItProvisioning {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    /** Users.id of the newly created portal account (null until account is created). */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "ms365_email", length = 255)
    private String ms365Email;

    @Column(name = "ms365_email_created_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime ms365EmailCreatedAt;

    // ── Hardware — V23: individual columns replaced by it_assets table ────────
    @OneToMany(mappedBy = "provisioning", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<ItAsset> assets = new java.util.ArrayList<>();

    @Column(name = "hardware_notes", length = 500, columnDefinition = "nvarchar(500)")
    private String hardwareNotes;

    // ── Licenses ──────────────────────────────────────────────────────────────
    @Column(name = "license_office365",  nullable = false) @Builder.Default private Boolean licenseOffice365  = false;
    @Column(name = "license_autocad",    nullable = false) @Builder.Default private Boolean licenseAutocad    = false;
    @Column(name = "license_revit",      nullable = false) @Builder.Default private Boolean licenseRevit      = false;
    @Column(name = "license_autodesk",   nullable = false) @Builder.Default private Boolean licenseAutodesk   = false;
    @Column(name = "license_kaspersky",  nullable = false) @Builder.Default private Boolean licenseKaspersky  = false;

    @Column(name = "license_other", length = 255, columnDefinition = "nvarchar(255)")
    private String licenseOther;

    // ── Active Directory ──────────────────────────────────────────────────────
    @Column(name = "ad_account_created", nullable = false) @Builder.Default private Boolean adAccountCreated = false;

    @Column(name = "ad_profile_type", length = 100, columnDefinition = "nvarchar(100)")
    private String adProfileType;

    @Column(name = "ad_account_created_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime adAccountCreatedAt;

    // ── Workflow ──────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private ItProvisioningStatus status = ItProvisioningStatus.PENDING;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime completedAt;

    @Column(name = "notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
