package com.daf360.rh.dto.onboarding;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SaveDraftRequest {
    // Section 2 — Employment
    private LocalDate hireDate;
    private String contractType;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private Boolean isOnProbation;
    private String grade;
    private String discipline;
    private String nogLevel;
    // Section 2c — Contrat. Draft state only; frozen onto the contract at completion.
    // The draft is stored as JSON in onboarding_drafts.draft_data, so these need no migration.
    private Integer    noticePeriodDays;
    private BigDecimal agreedNetSalary;
    private String     contractDocumentUrl;
    private String     contractDocumentName;
    // Section 3 — Regime
    private Long regimeTemplateId;
    private LocalDate regimeStartDate;
    // Section 4 — Personal & Social
    private String cnssNumber;
    private LocalDate cnssAffiliationDate;
    private String maritalStatus;
    private Integer numberOfChildren;
    private LocalDate dateOfBirth;
    private String gender;
    private String nationality;
    private String nationalId;
    private String passportNumber;
    private String personalAddress;
    // Section 5 — Bank / RIB
    private String bankName;
    private String bankAccountNumber;
    private String rib;
    private String iban;
    private String socialSecurityNumber;
    private String taxId;
    // Section 6 — Emergency contact
    private String emergencyContactName;
    private String emergencyContactRelation;
    private String emergencyContactPhone;
}
