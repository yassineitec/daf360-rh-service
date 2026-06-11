package com.daf360.rh.dto.profile;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class EmployeeListItemDto {
    private Long      profileId;       // employee_profiles.id — null if no profile yet
    private Long      userId;
    private String    fullName;
    private String    email;           // Users.username (MS365 email)
    private String    employeeId;      // Users.employee_id
    private Long      paysId;
    private String    paysLabel;       // pays.french_label
    private Long      roleId;
    private String    roleName;        // Roles.frenchName
    private String    lifecycleStatus;
    private String    contractType;
    private String    department;
    private String    grade;
    private String    discipline;
    private String    nogLevel;
    private LocalDate hireDate;
    private String    photoUrl;
    private String    gender;
    private boolean   hasProfile;      // true if employee_profiles record exists
    // total count for pagination (set by the count query, NOT from this row)
    private long      totalCount;
}
