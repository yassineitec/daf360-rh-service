package com.daf360.rh.dto.onboarding;

import com.daf360.rh.domain.enums.CandidateStatus;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data @Builder
public class OnboardingFormResponse {
    private Long paysId;

    // Section 1 — Identity (from candidate)
    private Long candidateId;
    private String firstName;
    private String lastName;
    private String emailPersonal;
    private String phone;
    private LocalDate dateOfBirth;
    private String nationality;
    private String nationalId;
    private String ms365Email;      // from it_provisioning

    /**
     * The vacancy this hire fills, carried through from the candidature so the last step of
     * recruitment still names the opening the first step started from. Null for a spontaneous
     * application.
     */
    private Long   recruitmentDemandId;
    private String recruitmentDemandJobTitle;

    // Section 2 — Employment
    private String appliedPosition;
    private String appliedGrade;
    private String appliedDiscipline;
    private String department;
    private String contractType;
    private LocalDate expectedStartDate;
    private LocalDate hireDate;
    private LocalDate contractEndDate;
    private LocalDate probationEndDate;
    private Boolean   isOnProbation;

    // Section 2b — Dimension FK IDs (for dropdowns)
    private Long gradeId;
    private Long disciplineId;
    private Long nogLevelId;
    private Long departmentId;

    // ── Section 2c — Contrat ─────────────────────────────────────────────────
    /**
     * What recruitment already decided — read-only evidence for the Contrat step. Never
     * echoed back on save.
     */
    private OnboardingRecruitmentDto recruitment;

    /**
     * Préavis to freeze on the contract, in calendar days. Prefilled from the accepted
     * offer, then the grade default; editable by RH here and NOT afterwards — a different
     * figure means a new contract.
     */
    private Integer    noticePeriodDays;
    /** Net salary agreed — persisted to the profile at completion, ending the retype. */
    private BigDecimal agreedNetSalary;
    /** Signed contract PDF, uploaded against the candidate before the profile exists. */
    private String     contractDocumentUrl;
    private String     contractDocumentName;

    // Section 3 — Working-time regime
    private List<RegimeSummary> availableRegimes;
    private Long selectedRegimeId;

    // Section 4 — Personal & Social
    private String    gender;
    private Long      nationalityId;
    private String    passportNumber;
    private String    personalAddress;
    private String    cnssNumber;
    private LocalDate cnssAffiliationDate;
    private String    maritalStatus;
    private Integer   numberOfChildren;
    // Section 5 — Bank / RIB
    private Long   bankId;
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

    // Section 7 — Documents (labels for upload slots)
    private List<String> requiredDocumentSlots;

    // Section 8 — Provisioning & HR timeline (nullable — absent until data is available)
    private String          matricule;
    private String          itDeviceName;
    private String          ms365LicenseType;
    private String          itProvisioningStatus;
    private OffsetDateTime  requestValidatedAt;
    private OffsetDateTime  itAccountCreatedAt;
    private OffsetDateTime  equipmentAssignedAt;

    // Meta
    private CandidateStatus candidateStatus;
    private boolean hasDraft;
    private OffsetDateTime draftSavedAt;
}
