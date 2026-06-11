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
