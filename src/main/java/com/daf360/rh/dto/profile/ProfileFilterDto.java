package com.daf360.rh.dto.profile;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ProfileFilterDto {
    private Long      paysId;
    private String    status;        // LifecycleStatus enum value
    private String    department;    // departments.label_fr
    private String    grade;         // grades.label_fr
    private String    contract;      // contract_type value
    private String    search;        // free-text — matches fullName / email via Users join
    private LocalDate hireDateFrom;  // inclusive
    private LocalDate hireDateTo;    // inclusive
}
