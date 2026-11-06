package com.daf360.rh.domain.enums;

/**
 * How a leave type picks its approvers, among the holders of the roles it names
 * (RoleApprovableAbsenceTypes). Applied by {@code LeaveApproverService.eligibleApproversFor}
 * since 2026-11-04 — the timesheet stored it and never read it.
 *
 * Only meaningful when the type names approver roles: a type with none uses the generic
 * hierarchy list whatever its strategy. Every mode keeps active users only and never offers
 * the employee themselves.
 */
public enum ApproverResolutionStrategy {
    /**
     * Up the org chart: holders of a named role among the employee's ANCESTOR roles (a peer
     * in the same role is not an approver), in the employee's country — except a root role
     * (PDG), whose holders count wherever they are, as in the generic list. The default.
     */
    TOP_OF_HIERARCHY,
    /**
     * Whoever holds a named role in the employee's country, hierarchy ignored — for a type
     * that a function signs off (HR for sick leave) rather than the line above the employee.
     */
    ROLE_MATCH,
    /**
     * Holders of a named role in the employee's country; when there are none, holders in any
     * other country — for a role that exists in one entity only.
     */
    SAME_COUNTRY_FIRST
}
