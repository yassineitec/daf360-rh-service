package com.daf360.rh.domain.enums;

/**
 * How a leave type picks its approvers.
 *
 * ⚠️ STORED BUT NOT ENFORCED — on master (2026-08-18) this field is written and edited
 * through the absence-type admin screen and read by no resolution code at all.
 * {@code AbsenceTypeServiceImpl.getEligibleApprovers} ignores it entirely and always walks
 * the employee's role hierarchy upward, keeping ancestors whose role is configured as an
 * approver for the type.
 *
 * Carried across so the column round-trips and the admin screen keeps working. Do not build
 * behaviour on it without deciding what the other two modes should actually mean.
 */
public enum ApproverResolutionStrategy {
    /** The only one with an implementation: walk up the org chart from the employee. */
    TOP_OF_HIERARCHY,
    /** First active user holding the configured role in the same country. Unimplemented. */
    ROLE_MATCH,
    /** Any user with the role, filtered strictly by country. Unimplemented. */
    SAME_COUNTRY_FIRST
}
