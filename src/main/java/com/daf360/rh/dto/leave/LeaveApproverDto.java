package com.daf360.rh.dto.leave;

/**
 * Someone who may approve a given employee's leave, as offered by the request form.
 *
 * Derived from the role hierarchy rather than read from a manager column — there is none.
 * See {@link com.daf360.rh.service.LeaveApproverService}.
 */
public record LeaveApproverDto(
        Long userId,
        String fullName,
        String email,
        String roleName
) {}
