package com.daf360.rh.lists;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "configurable_list_values")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigurableListValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_type_id", nullable = false)
    private Long listTypeId;

    @Column(name = "pays_id")
    private Long paysId;

    @Column(name = "value_code", length = 100, nullable = false)
    private String valueCode;

    @Column(name = "label_fr", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String labelEn;

    @Column(name = "sort_order", nullable = false, columnDefinition = "INT DEFAULT 0")
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false, columnDefinition = "BIT DEFAULT 1")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_system", nullable = false, columnDefinition = "BIT DEFAULT 0")
    @Builder.Default
    private Boolean isSystem = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "DATETIMEOFFSET(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "payroll_contract_code", length = 20)
    private String payrollContractCode;

    /**
     * CONTRACT_TYPE only: which lifecycle rules a contract of this type follows — one of
     * {@link ContractTypeRefs#NATURES} (CDI, CDD, CIVP, STAGE, FREELANCE, DETACHEMENT).
     * Lets a type added in the admin (« contrat ») behave like a CDI, a CDD…
     */
    @Column(name = "lifecycle_nature", length = 30)
    private String lifecycleNature;

    /**
     * CONTRACT_DECISION only: what the choice does — a
     * {@link com.daf360.rh.lifecycle.ContractDecisionAction} code. The label stays free.
     */
    @Column(name = "decision_action", length = 30)
    private String decisionAction;

    /*
     * CONTRACT_TYPE only: this type's own « Décider » rules. All NULL = the type inherits the
     * rules of its nature (contract_type_config, Échéances de contrat) — see ContractRules.
     * trialDecisions / endDecisions hold a CSV of ContractDecisionAction codes ('' = none).
     */
    @Column(name = "trial_renewable")
    private Boolean trialRenewable;

    @Column(name = "trial_max_renewals")
    private Integer trialMaxRenewals;

    @Column(name = "trial_decisions", columnDefinition = "NVARCHAR(500)")
    private String trialDecisions;

    @Column(name = "end_decisions", columnDefinition = "NVARCHAR(500)")
    private String endDecisions;

    /** Whether this type has its own « Décider » rules instead of its nature's. */
    public boolean hasOwnDecisionRules() {
        return trialRenewable != null || trialMaxRenewals != null || trialDecisions != null || endDecisions != null;
    }

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
