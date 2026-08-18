package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[it_asset_assignments] — created in V76.
 *
 * The IT equipment LEDGER: one row = one item held by one employee over
 * [assignedAt, returnedAt]. Several rows of the same type on the same employee are
 * normal (a replaced laptop, a second monitor, a phone returned then reassigned).
 *
 * Not to be confused with {@link ItAsset}, which backs the IT provisioning FORM:
 * that one hangs off a single {@link ItProvisioning} per candidate, is unique per
 * (provisioning, type) and carries no dates — so it can only ever describe what was
 * handed over at hire time. This table is what makes a history possible; ItAsset
 * stays the input form and seeds rows here.
 */
@Entity
@Table(name = "it_asset_assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItAssetAssignment {

    /** CK_it_asg_status. ASSIGNED is the only one with a null returnedAt. */
    public static final String STATUS_ASSIGNED    = "ASSIGNED";
    public static final String STATUS_RETURNED    = "RETURNED";
    public static final String STATUS_LOST        = "LOST";
    public static final String STATUS_WRITTEN_OFF = "WRITTEN_OFF";

    /** CK_it_asg_source — where the row came from, so a manual correction is visible as one. */
    public static final String SOURCE_ONBOARDING  = "ONBOARDING";
    public static final String SOURCE_MANUAL      = "MANUAL";
    public static final String SOURCE_OFFBOARDING = "OFFBOARDING";
    public static final String SOURCE_IMPORT      = "IMPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_profile_id", nullable = false)
    private Long employeeProfileId;

    @Column(name = "asset_type_id", nullable = false)
    private Long assetTypeId;

    /**
     * Business key of the physical object when there is one. A filtered unique index
     * (UQ_it_asg_active_serial) stops the same serial being held by two people at once.
     */
    @Column(name = "serial_number", length = 100, columnDefinition = "nvarchar(100)")
    private String serialNumber;

    @Column(name = "brand_model", length = 150, columnDefinition = "nvarchar(150)")
    private String brandModel;

    @Column(name = "asset_tag", length = 100, columnDefinition = "nvarchar(100)")
    private String assetTag;

    @Column(name = "assigned_at", nullable = false)
    private LocalDate assignedAt;

    /** Null while the item is still held. CK_it_asg_closed keeps this in step with status. */
    @Column(name = "returned_at")
    private LocalDate returnedAt;

    @Column(name = "condition_on_assign", nullable = false, length = 50,
            columnDefinition = "nvarchar(50)")
    @Builder.Default
    private String conditionOnAssign = "BON_ETAT";

    @Column(name = "condition_on_return", length = 50, columnDefinition = "nvarchar(50)")
    private String conditionOnReturn;

    @Column(name = "status", nullable = false, length = 30, columnDefinition = "nvarchar(30)")
    @Builder.Default
    private String status = STATUS_ASSIGNED;

    @Column(name = "source", nullable = false, length = 30, columnDefinition = "nvarchar(30)")
    @Builder.Default
    private String source = SOURCE_MANUAL;

    /** Set on rows seeded from the IT provisioning form; also the de-duplication key there. */
    @Column(name = "it_provisioning_id")
    private Long itProvisioningId;

    /** Set when the row was closed by an offboarding asset return. */
    @Column(name = "offboarding_return_id")
    private Long offboardingReturnId;

    @Column(name = "assigned_by")
    private Long assignedBy;

    @Column(name = "returned_by")
    private Long returnedBy;

    @Column(name = "notes", length = 500, columnDefinition = "nvarchar(500)")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
