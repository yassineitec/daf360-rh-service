package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[contract_type_config] — per-pays configuration for each contract type.
 * Created in Part 1 DB migration (V32). Seeded for pays_id=179 (Tunisie).
 */
@Entity
@Table(name = "contract_type_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractTypeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** CDI | CDD | CIVP | STAGE | FREELANCE | DETACHEMENT */
    @Column(name = "contract_type_code", nullable = false, length = 30)
    private String contractTypeCode;

    @Column(name = "trial_period_days_standard")
    private Integer trialPeriodDaysStandard;

    @Column(name = "trial_period_days_manager")
    private Integer trialPeriodDaysManager;

    @Column(name = "trial_period_renewable", nullable = false)
    @Builder.Default
    private Boolean trialPeriodRenewable = false;

    @Column(name = "alert_days_before_expiry", nullable = false)
    @Builder.Default
    private Integer alertDaysBeforeExpiry = 30;

    @Column(name = "indemnity_rate_pct", precision = 6, scale = 4)
    private BigDecimal indemnityRatePct;

    @Column(name = "indemnity_applicable", nullable = false)
    @Builder.Default
    private Boolean indemnityApplicable = false;

    @Column(name = "civp_max_age")
    private Integer civpMaxAge;

    @Column(name = "civp_max_duration_months")
    private Integer civpMaxDurationMonths;

    @Column(name = "civp_aneti_required", nullable = false)
    @Builder.Default
    private Boolean civpAnetiRequired = false;

    @Column(name = "stage_max_duration_months")
    private Integer stageMaxDurationMonths;

    @Column(name = "stage_min_gratification_months")
    private Integer stageMinGratificationMonths;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
