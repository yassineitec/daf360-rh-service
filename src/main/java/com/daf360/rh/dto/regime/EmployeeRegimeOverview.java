package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmployeeRegimeOverview {
    private Long employeeProfileId;
    private Long userId;
    private String fullName;
    private String roleName;
    private Long resolvedRegimeId;
    private String resolvedRegimeLabelFr;
    private String assignmentLevel; // EMPLOYEE_OVERRIDE | ROLE_ASSIGNMENT | DEFAULT | NONE
}
