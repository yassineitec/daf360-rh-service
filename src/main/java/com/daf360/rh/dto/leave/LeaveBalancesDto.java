package com.daf360.rh.dto.leave;

/**
 * What the employee has left, in days.
 *
 * Nulls are meaningful and are NOT defaulted to zero here: 107 of the timesheet's 222 users
 * and 135 of DAF360_HR's 260 have no congé balance recorded at all, and "not set" is a
 * different statement from "none remaining". The modal shows a dash for null and a figure
 * for zero; collapsing them would tell 135 people they have run out of leave.
 */
public record LeaveBalancesDto(
        Double soldeConge,
        Double soldeMaladie,
        Double soldeTeletravail
) {}
