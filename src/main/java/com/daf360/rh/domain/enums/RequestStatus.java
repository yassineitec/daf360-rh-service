package com.daf360.rh.domain.enums;

/**
 * Lifecycle states for employee_requests.status (varchar 50).
 *
 * Transitions:
 *   SUBMITTED → IN_REVIEW → APPROVED | REJECTED
 *   SUBMITTED → CANCELLED (by employee, only from SUBMITTED)
 *   IN_REVIEW → PENDING_L2 (when type.approval_level = L2, after L1 approval)
 *   PENDING_L2 → APPROVED | REJECTED
 */
public enum RequestStatus {
    SUBMITTED,
    IN_REVIEW,
    PENDING_L2,
    APPROVED,
    REJECTED,
    CANCELLED
}
