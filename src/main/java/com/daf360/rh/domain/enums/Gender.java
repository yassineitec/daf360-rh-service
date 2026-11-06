package com.daf360.rh.domain.enums;

/**
 * Who an absence type is available to. The timesheet stored it and never checked it; rh
 * enforces it since 2026-11-04 (LeaveRequestService.genderAllows), letting an employee
 * whose gender is not recorded through.
 */
public enum Gender {
    MALE,
    FEMALE,
    ALL
}
