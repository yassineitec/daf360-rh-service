package com.daf360.rh.dto.leave;

import java.util.List;

/**
 * One leave type as the admin screen reads it — every configurable field, plus the roles
 * allowed to approve it.
 *
 * Two fields are returned but have no effect anywhere: `allowedGender` and
 * `approverResolutionStrategy` are stored and editable, and no code reads them. They are
 * surfaced so the screen round-trips existing data rather than silently blanking it, and the
 * UI labels them as not yet enforced — a field that looks like a rule but is not is worse
 * than one that is absent.
 */
public record AbsenceTypeDto(
        Long id,
        String code,
        String labelFr,
        String labelEn,
        boolean active,
        boolean tracksBalance,
        String balanceField,
        boolean includedInHrStats,
        boolean requiresJustification,
        Integer maxDays,
        int displayOrder,
        String allowedGender,
        boolean managerCanView,
        String approverResolutionStrategy,
        /** Roles permitted to approve this type. Empty means "no restriction". */
        List<ApproverRoleDto> approverRoles
) {
    public record ApproverRoleDto(Long roleId, String roleName) {}
}
