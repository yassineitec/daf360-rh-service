package com.daf360.rh.dto.profile;

import com.daf360.rh.validator.ValidEmployeeId;
import com.daf360.rh.validator.ValidFixedTermContract;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@ValidFixedTermContract
public class EmployeeProfileCreateDto {

    /** FK → Users.id */
    @NotNull(message = "userId est obligatoire")
    private Long userId;

    /** FK → pays.id */
    @NotNull(message = "paysId est obligatoire")
    private Long paysId;

    /** Formatted employee ID stored in Users.employee_id — format: <ENTITY>-<YY>-<NNNN> */
    @NotBlank(message = "employeeId est obligatoire")
    @ValidEmployeeId
    private String employeeId;

    @NotNull(message = "La date d'embauche est obligatoire")
    @PastOrPresent(message = "La date d'embauche ne peut pas être dans le futur")
    private LocalDate hireDate;

    @NotBlank(message = "Le type de contrat est obligatoire")
    @Pattern(regexp = "PERMANENT|FIXED_TERM|INTERN|CONSULTANT",
             message = "Type de contrat invalide — valeurs: PERMANENT, FIXED_TERM, INTERN, CONSULTANT")
    private String contractType;

    /** Required when contractType = FIXED_TERM — enforced by @ValidFixedTermContract. */
    private LocalDate contractEndDate;

    private LocalDate probationEndDate;

    private Long departmentId;

    private Long gradeId;

    private Long disciplineId;

    private Long nogLevelId;

    // ── Personal info ────────────────────────────────────────────────────────
    private LocalDate dateOfBirth;

    @Pattern(regexp = "MALE|FEMALE|OTHER|UNSPECIFIED", flags = Pattern.Flag.CASE_INSENSITIVE,
             message = "Genre invalide")
    private String gender;

    private Long nationalityId;

    @Email @Size(max = 255) private String personalEmail;

    @Size(max = 50) private String phone;

    @Size(max = 500) private String personalAddress;

    // ── Emergency contact ────────────────────────────────────────────────────
    @Size(max = 255) private String emergencyContactName;
    @Size(max = 100) private String emergencyContactRelation;
    @Size(max = 50)  private String emergencyContactPhone;

    // ── Financial — visible to HR_MANAGER/FINANCE_OFFICER only ──────────────
    private Long bankId;

    @Pattern(regexp = "[A-Z]{2}\\d{2}[A-Z0-9]{4,30}",
             message = "Format IBAN invalide")
    private String iban;

    @Size(max = 100) private String bankAccountNumber;
    @Size(max = 100) private String rib;

    // ── Identity documents — HR_MANAGER only ────────────────────────────────
    @Size(max = 100) private String nationalId;
    @Size(max = 100) private String passportNumber;
    @Size(max = 100) private String socialSecurityNumber;
    @Size(max = 100) private String taxId;

    @Size(max = 500) private String photoUrl;
}
