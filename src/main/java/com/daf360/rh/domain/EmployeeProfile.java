package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.LifecycleStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// NOTE: nationality, grade, discipline, nogLevel, department, bankName TEXT columns
// were dropped in V23 migration and replaced with FK references to dimension tables.

/**
 * Maps the shared [employee_profiles] table created by the portal backend.
 * Contains the full HR profile for an employee (linked to Users via user_id).
 *
 * Read/write from the rh-service — do not drop or rename columns.
 * Sensitive fields: iban, bank_account_number, rib, social_security_number, national_id
 *   must never be returned in API responses without ROLE_HR_ADMIN.
 */
@Entity
@Table(name = "employee_profiles")
@SQLRestriction("deleted = 0")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 50)
    @Builder.Default
    private LifecycleStatus lifecycleStatus = LifecycleStatus.PRE_ONBOARDING;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "contract_type", length = 50)
    private String contractType;

    @Column(name = "contract_end_date")
    private LocalDate contractEndDate;

    @Column(name = "probation_end_date")
    private LocalDate probationEndDate;

    @Column(name = "is_on_probation", nullable = false)
    private Boolean isOnProbation;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 20)
    private String gender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nationality_id")
    private Nationality nationality;

    @Column(name = "national_id", length = 100)
    private String nationalId;

    @Column(name = "passport_number", length = 100)
    private String passportNumber;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "personal_email", length = 255)
    private String personalEmail;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "home_address", length = 500, columnDefinition = "nvarchar(500)")
    private String homeAddress;

    @Column(name = "emergency_contact_name", length = 255)
    private String emergencyContactName;

    @Column(name = "emergency_contact_relation", length = 100)
    private String emergencyContactRelation;

    @Column(name = "emergency_contact_phone", length = 50)
    private String emergencyContactPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private HrDepartment department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grade_id")
    private Grade grade;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discipline_id")
    private Discipline discipline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "nog_level_id")
    private NogLevel nogLevel;

    @Column(name = "regime_template_id")
    private Long regimeTemplateId;

    @Column(name = "regime_start_date")
    private LocalDate regimeStartDate;

    @Column(name = "regime_end_date")
    private LocalDate regimeEndDate;

    @Column(name = "regime_reason", length = 255, columnDefinition = "nvarchar(255)")
    private String regimeReason;

    // ── Sensitive financial fields — restrict to HR_ADMIN role ──────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_id")
    private Bank bank;

    @Column(name = "iban", length = 100)
    private String iban;

    @Column(name = "bank_account_number", length = 100)
    private String bankAccountNumber;

    @Column(name = "rib", length = 100)
    private String rib;

    @Column(name = "social_security_number", length = 100)
    private String socialSecurityNumber;

    @Column(name = "tax_id", length = 100)
    private String taxId;

    @Column(name = "cnss_number", length = 100)
    private String cnssNumber;

    @Column(name = "cnss_affiliation_date")
    private LocalDate cnssAffiliationDate;

    @Column(name = "marital_status", length = 30)
    private String maritalStatus;

    @Column(name = "number_of_children")
    private Integer numberOfChildren;



    @Column(name = "candidate_id")
    private Long candidateId;

    // ── Employee Lifecycle Engine (V32) ───────────────────────────────────────
    @Column(name = "current_contract_id")
    private Long currentContractId;

    /** New lifecycle engine status code (from configurable_list_values). Nullable.
     *  Distinct from lifecycleStatus (legacy NOT NULL enum). */
    @Column(name = "lifecycle_status_code", length = 50)
    private String lifecycleStatusCode;

    @Column(name = "onboarding_completed", nullable = false)
    @Builder.Default
    private Boolean onboardingCompleted = false;

    @Column(name = "onboarding_completed_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime onboardingCompletedAt;

    // ── Salaires proposés ──────────────────────────────────────────────────────
    @Column(name = "salaire_net_candidat", precision = 10, scale = 3)
    private java.math.BigDecimal salaireNetCandidat;

    @Column(name = "salaire_net_rh", precision = 10, scale = 3)
    private java.math.BigDecimal salaireNetRh;

    @Column(name = "deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime deletedAt;
}
