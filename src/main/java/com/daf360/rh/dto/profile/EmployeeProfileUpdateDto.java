package com.daf360.rh.dto.profile;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * PATCH semantics: only non-null fields are applied.
 * Sensitive financial/identity fields require the HR_UPDATE_PROFILE or HR_ADMIN_ROLES permission.
 */
@Data
public class EmployeeProfileUpdateDto {

    // ── Contract ──────────────────────────────────────────────────────────
    @Pattern(regexp = "PERMANENT|FIXED_TERM|INTERN|CONSULTANT",
             message = "Type de contrat invalide — valeurs : PERMANENT, FIXED_TERM, INTERN, CONSULTANT")
    private String contractType;

    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private LocalDate hireDate;
    private Boolean   isOnProbation;

    // ── Position ──────────────────────────────────────────────────────────
    private Long departmentId;
    private Long gradeId;
    private Long disciplineId;
    private Long nogLevelId;
    private Long regimeTemplateId;
    private LocalDate regimeStartDate;
    private LocalDate regimeEndDate;
    @Size(max = 255) private String regimeReason;

    // ── Personal ──────────────────────────────────────────────────────────
    private LocalDate dateOfBirth;
    @Size(max = 20)
    private String gender;  // valeur libre — ex: Homme, Femme, Autre, Non précisé
    private Long nationalityId;
    @Email @Size(max = 255) private String personalEmail;
    @Size(max = 50)  private String phone;
    @Size(max = 500) private String personalAddress;
    @Size(max = 500) private String photoUrl;
    @Size(max = 30)  private String maritalStatus;  // valeur libre — ex: Célibataire, Marié(e), Divorcé(e), Veuf(ve)
    private Integer numberOfChildren;

    // ── Emergency contact ─────────────────────────────────────────────────
    @Size(max = 255) private String emergencyContactName;
    @Size(max = 100) private String emergencyContactRelation;
    @Size(max = 50)  private String emergencyContactPhone;

    // ── Sensitive — HR_MANAGER / FINANCE_OFFICER only ─────────────────────
    private Long bankId;
    @Size(max = 50)
    private String iban;
    @Size(max = 100) private String bankAccountNumber;
    @Size(max = 100) private String rib;
    @Size(max = 100) private String nationalId;
    @Size(max = 100) private String passportNumber;
    @Size(max = 100) private String socialSecurityNumber;
    @Size(max = 100) private String taxId;
    @Size(max = 100) private String cnssNumber;
    private LocalDate cnssAffiliationDate;

    // ── Salaires proposés ─────────────────────────────────────────────────────
    private java.math.BigDecimal salaireNetCandidat;
    private java.math.BigDecimal salaireNetRh;

    /** Reason for update — written to audit_log. */
    @NotBlank(message = "La raison de la modification est obligatoire")
    @Size(max = 500)
    private String reason;
}
