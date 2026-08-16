package com.daf360.rh.dto.pdf;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class EmployeeDataDto {
    private Long   employeeProfileId;
    private Long   userId;
    private Long   paysId;
    private Long   candidateId;

    private String fullName;
    /** Séparés de fullName pour construire le nom de dossier SharePoint "Prénom NOM" (nom de
     * famille en majuscules) — fullName ne garantit pas cette casse/cet ordre exacts. */
    private String firstName;
    private String lastName;
    private String ms365Email;
    private String isoCode;
    private String paysLabel;

    private String gender;
    private String nationalId;
    private String cinCity;
    private String cinDate;

    private String grade;
    private String discipline;
    private String contractType;

    private LocalDate hireDate;
    private LocalDate probationEndDate;
    private LocalDate contractEndDate;

    private String bankName;
    private String rib;
    private String iban;
}
