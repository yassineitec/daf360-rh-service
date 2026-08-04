package com.daf360.rh.common;

import java.util.Map;

/**
 * Which permission lets you act on the offboarding stage that owns a given task.
 *
 * Server-side twin of `STAGE_PERMISSIONS` / `STAGES[].taskCodes` in the frontend's
 * `offboarding-display.ts`. Both have to agree: the frontend map decides which stage
 * bodies a user sees, this one decides whether the call is allowed. If you regroup a
 * task code into a different stage, change both.
 *
 * Why a task-code map rather than reading `offboarding_tasks.owner_role`: `owner_role`
 * is a free NVARCHAR with no join to `Roles`, so it can name an owner but can never be
 * an authorization input. See V58 for the reasoning behind the three stage codes.
 */
public final class OffboardingStagePermissions {

    private OffboardingStagePermissions() {}

    /** The 9 codes V44 seeds, grouped exactly as the wizard's 7 stages group them. */
    private static final Map<String, String> BY_TASK_CODE = Map.of(
        // Stage 3 — Passation
        "KNOWLEDGE_TRANSFER",    PermissionCatalog.RH_OFFBOARDING_STAGE_HANDOVER,
        // Stage 4 — Informatique & Matériel
        "ASSET_RETURN_IT",       PermissionCatalog.RH_OFFBOARDING_STAGE_IT,
        "ASSET_RETURN_BADGE",    PermissionCatalog.RH_OFFBOARDING_STAGE_IT,
        "IT_ACCESS_REVOKE",      PermissionCatalog.RH_OFFBOARDING_STAGE_IT,
        // Stage 5 — Kit RH
        "EXIT_INTERVIEW",        PermissionCatalog.RH_CONDUCT_EXIT_INTERVIEW,
        "WORK_CERTIFICATE",      PermissionCatalog.RH_MANAGE_OFFBOARDING,
        "INTERNAL_ANNOUNCEMENT", PermissionCatalog.RH_MANAGE_OFFBOARDING,
        // Stage 6 — Paie & STC
        "FINAL_SETTLEMENT",      PermissionCatalog.RH_OFFBOARDING_STAGE_PAYROLL,
        "EXPENSE_CLOSE",         PermissionCatalog.RH_OFFBOARDING_STAGE_PAYROLL
    );

    /**
     * Falls back to `RH_MANAGE_OFFBOARDING` for a code this map does not know.
     *
     * The task catalog is admin-editable, so a new `task_code` can appear at any time
     * without a code change. Defaulting to the RH-only permission fails closed: the new
     * task is actionable by RH and nobody else until it is deliberately assigned to a
     * stage here. The alternative — defaulting to "anyone with any stage right" — would
     * let a fresh catalog row silently widen access.
     */
    public static String forTaskCode(String taskCode) {
        if (taskCode == null) return PermissionCatalog.RH_MANAGE_OFFBOARDING;
        return BY_TASK_CODE.getOrDefault(taskCode, PermissionCatalog.RH_MANAGE_OFFBOARDING);
    }

    /**
     * Checklist group → the permission that may edit it, matching the stage that renders it:
     * HANDOVER on stage 3, ACCESS on stage 4, KIT on stage 5.
     *
     * Same fail-closed default as above: an unrecognised group is RH-only.
     */
    private static final Map<String, String> BY_CHECKLIST_GROUP = Map.of(
        "HANDOVER", PermissionCatalog.RH_OFFBOARDING_STAGE_HANDOVER,
        "ACCESS",   PermissionCatalog.RH_OFFBOARDING_STAGE_IT,
        "KIT",      PermissionCatalog.RH_MANAGE_OFFBOARDING
    );

    public static String forChecklistGroup(String groupCode) {
        if (groupCode == null) return PermissionCatalog.RH_MANAGE_OFFBOARDING;
        return BY_CHECKLIST_GROUP.getOrDefault(groupCode, PermissionCatalog.RH_MANAGE_OFFBOARDING);
    }
}
