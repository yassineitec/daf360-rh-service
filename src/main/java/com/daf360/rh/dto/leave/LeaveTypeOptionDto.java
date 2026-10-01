package com.daf360.rh.dto.leave;

import java.util.List;

/**
 * One selectable leave type, with everything the form needs to behave correctly for it.
 *
 * Richer than a {label, value} pair because a type is not just a label any more: it decides
 * whether a balance is shown, whether a justification is mandatory, how many days may be
 * asked for, and — the important one — WHO may approve it.
 *
 * APPROVERS ARE PER TYPE
 * -----------------------------------------------------------------------------
 * A type may name the roles that can approve it. When it does, {@link #eligibleApprovers} is
 * the resolved list for this employee and the generic manager list does not apply; when it
 * does not, the field is null and the form falls back to the hierarchy walk. Null and empty
 * mean different things here and must not be collapsed: null is "this type has no approver
 * roles configured, use the default list", empty is "it has them and nobody above this
 * employee holds one" — which is a configuration problem the employee should be told about
 * rather than silently offered the wrong approvers.
 *
 * {@link #autoAssign} is true when exactly one role is configured: the form selects the only
 * possible approver rather than asking a question with one answer.
 */
public record LeaveTypeOptionDto(
        String code,
        String label,
        boolean autoAssign,
        int approverRoleCount,
        List<LeaveApproverDto> eligibleApprovers,
        boolean tracksBalance,
        /** Which balance it draws on — CONGE or MALADIE — or null when it draws on none. */
        String balanceField,
        boolean requiresJustification,
        Integer maxDays,
        /**
         * The scheduling rules that apply to this type, RESOLVED — the type's own value, or
         * the country's when the type leaves it unset. Sent resolved rather than raw so the
         * form can state the rule it will be judged by instead of re-deriving the fallback.
         * Null or 0 means no rule.
         */
        Integer advanceNoticeDays,
        Integer leaveGapDays,
        /** What this type is for, in a sentence, or null. */
        String description
) {}
