package com.daf360.rh.dto.onboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;

@Data
public class CompleteProfileRequest {
    // Section 2 — Employment (mandatory)
    @NotNull  private LocalDate hireDate;
    @NotBlank private String contractType;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    @NotNull  private Boolean isOnProbation;

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
