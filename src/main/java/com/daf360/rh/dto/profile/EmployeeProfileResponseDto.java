package com.daf360.rh.dto.profile;

import com.daf360.rh.domain.enums.LifecycleStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class EmployeeProfileResponseDto {

    private Long   id;
    private Long   userId;
    /**
     * The entity's id, not just its label.
     *
     * Every write the profile page makes on the employee's behalf needs it — creating a
     * contract, assigning a regime — and it was absent while `paysLabel` was present, so the
     * frontend's `EmployeeProfile.paysId` typed fine and was `undefined` at runtime. That is
     * what made POST /lifecycle/contracts reject `paysId: ne doit pas être nul`.
     */
    private Long   paysId;
    private String   paysLabel;
    private LifecycleStatus lifecycleStatus;

    // ── Identité ──────────────────────────────────────────────────────────────
    /** employee_profiles.payroll_matricule. Was Users.employee_id, which is NULL for
     *  every row in prod — the field rendered blank on the detail page for all 158
     *  profiles. Kept under the name `matricule` so the frontend binding is unchanged. */
    private String matricule;
    private String fullName;   // Users.fullName

    // ── Contract ──────────────────────────────────────────────────────────
    private LocalDate hireDate;
    private String    contractType;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private Boolean   isOnProbation;

    // ── Position ──────────────────────────────────────────────────────────
    private Long   departmentId;
    private String department;
    private Long   gradeId;
    private String grade;
    private Long   disciplineId;
    private String discipline;
    private Long   nogLevelId;
    private String nogLevel;
    private Long   regimeTemplateId;
    private String regimeLabelFr;
    private LocalDate regimeStartDate;
    private LocalDate regimeEndDate;
    private String regimeReason;

    // ── Personal ──────────────────────────────────────────────────────────
    private LocalDate dateOfBirth;
    private String    gender;
    private Long      nationalityId;
    private String    nationality;
    private String    personalEmail;
    private String    phone;          // professionnel
    private String    personalPhone;  // personnel
    private String    personalAddress;
    private String    photoUrl;

    // ── Emergency contact ─────────────────────────────────────────────────
    private String emergencyContactName;
    private String emergencyContactRelation;
    private String emergencyContactPhone;

    // ── Sensitive — masked unless caller has HR_MANAGER / FINANCE_OFFICER ─
    private String nationalId;
    private String passportNumber;
    private Long   bankId;
    private String bankName;
    private String iban;
    private String bankAccountNumber;
    private String rib;
    private String socialSecurityNumber;
    private String taxId;

    // ── Onboarding & administratif ────────────────────────────────────────────
    private String        cnssNumber;
    private java.time.LocalDate cnssAffiliationDate;
    private String        maritalStatus;
    private Integer       numberOfChildren;
    private Boolean       onboardingCompleted;
    private OffsetDateTime onboardingCompletedAt;

    // ── Salaires ──────────────────────────────────────────────────────────────
    private java.math.BigDecimal salaireNetCandidat;
    private java.math.BigDecimal salaireNetRh;

    // ── Audit ─────────────────────────────────────────────────────────────
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
