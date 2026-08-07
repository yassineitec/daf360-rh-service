package com.daf360.rh.dto.onboarding;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CompleteProfileRequest {
    // Section 2 — Employment (mandatory)
    @NotNull  private LocalDate hireDate;
    @NotBlank private String contractType;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    @NotNull  private Boolean isOnProbation;

    // ── Section 2c — Contrat (V69) ────────────────────────────────────────────
    /**
     * Préavis agreed with the employee, in calendar days — frozen onto the contract this
     * completion creates and NOT editable afterwards.
     *
     * Null is allowed and means "no figure confirmed": the contract then resolves it from the
     * accepted offer, then the grade default. That is deliberate — a wizard finished before
     * this step existed must not be blocked, and a null here is honest about not knowing.
     */
    @Min(value = 0, message = "Le préavis ne peut pas être négatif.")
    private Integer noticePeriodDays;

    /** Net salary agreed — written to the profile so the negotiated figure stops being retyped. */
    @DecimalMin(value = "0", message = "Le salaire ne peut pas être négatif.")
    private BigDecimal agreedNetSalary;

    /** Signed contract PDF, staged against the candidate; linked to the profile on completion. */
    private String contractDocumentUrl;
    private String contractDocumentName;

    // Dimension FK IDs (V23) — optional, HR patches later via PATCH /profiles/{id}
    private Long   gradeId;
    private Long   disciplineId;
    private Long   nogLevelId;
    private Long   departmentId;
    private Long   nationalityId;
    private Long   bankId;

    // Section 3 — Regime (mandatory)
    @NotNull private Long regimeTemplateId;
    private LocalDate regimeStartDate;

    // Section 4 — Personal & Social
    @NotBlank private String cnssNumber;
    private LocalDate cnssAffiliationDate;
    private String maritalStatus;
    private Integer numberOfChildren;
    private LocalDate dateOfBirth;
    private String gender;
    private String nationalId;
    private String passportNumber;
    private String personalAddress;

    // Section 5 — Bank / RIB (mandatory)
    @NotBlank private String rib;
    private String bankAccountNumber;
    private String iban;
    private String socialSecurityNumber;
    private String taxId;

    // Section 6 — Emergency contact
    private String emergencyContactName;
    private String emergencyContactRelation;
    private String emergencyContactPhone;
}
