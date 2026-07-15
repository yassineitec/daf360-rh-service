package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[offboarding_task_catalog].
 * Template tasks seeded per pays × contract_type.
 * Created in V38 migration.
 */
@Entity
@Table(name = "offboarding_task_catalog")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffboardingTaskCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** CDI | CDD | STAGE | FREELANCE | CIVP */
    @Column(name = "contract_type", nullable = false, length = 50)
    private String contractType;

    @Column(name = "task_code", nullable = false, length = 50)
    private String taskCode;

    @Column(name = "task_label", nullable = false, length = 255,
            columnDefinition = "nvarchar(255)")
    private String taskLabel;

    @Column(name = "owner_role", nullable = false, length = 100,
            columnDefinition = "nvarchar(100)")
    private String ownerRole;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private Boolean isMandatory = true;

    @Column(name = "is_blocking", nullable = false)
    @Builder.Default
    private Boolean isBlocking = false;

    @Column(name = "sla_working_days", nullable = false)
    @Builder.Default
    private Integer slaWorkingDays = 5;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
