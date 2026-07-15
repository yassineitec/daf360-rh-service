package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_asset_returns].
 * Tracks physical/digital assets to be returned during offboarding.
 * Created in V38 migration.
 */
@Entity
@Table(name = "offboarding_asset_returns")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingAssetReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_instance_id", nullable = false)
    private Long workflowInstanceId;

    /** Optional link to the ASSET_RETURN_IT or ASSET_RETURN_BADGE task */
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "asset_description", nullable = false, length = 255,
            columnDefinition = "nvarchar(255)")
    private String assetDescription;

    /** IT | BADGE | VEHICLE | OTHER */
    @Column(name = "asset_type", nullable = false, length = 50)
    @Builder.Default
    private String assetType = "IT";

    @Column(name = "expected_return_date", nullable = false)
    private LocalDate expectedReturnDate;

    @Column(name = "actual_return_date")
    private LocalDate actualReturnDate;

    @Column(name = "condition_on_return", length = 100, columnDefinition = "nvarchar(100)")
    private String conditionOnReturn;

    @Column(name = "confirmed_by")
    private Long confirmedBy;

    @Column(name = "confirmed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime confirmedAt;

    @Column(name = "is_written_off", nullable = false)
    @Builder.Default
    private Boolean isWrittenOff = false;

    @Column(name = "write_off_approved_by")
    private Long writeOffApprovedBy;

    @Column(name = "write_off_reason", length = 500, columnDefinition = "nvarchar(500)")
    private String writeOffReason;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
