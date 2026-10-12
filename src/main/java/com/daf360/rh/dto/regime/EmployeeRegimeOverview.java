package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmployeeRegimeOverview {
    private Long employeeProfileId;
    private Long userId;
    /** Users.fullName; null when the profile has no user name (the UI shows "Profil N"). */
    private String fullName;
    private String roleName;
    /** Roles.englishName — null when the role has none (the UI then shows roleName). */
    private String roleNameEn;
    private Long resolvedRegimeId;
    private String resolvedRegimeLabelFr;
    private String assignmentLevel; // EMPLOYEE_OVERRIDE | ROLE_ASSIGNMENT | DEFAULT | NONE
}
