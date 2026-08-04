package com.daf360.rh.domain.enums;

/**
 * Employee profile lifecycle state machine.
 *
 * Allowed transitions:
 *   PRE_ONBOARDING → ACTIVE
 *   ACTIVE         → ON_LEAVE | ON_MISSION | OFFBOARDING
 *   ON_LEAVE       → ACTIVE | OFFBOARDING
 *   ON_MISSION     → ACTIVE | OFFBOARDING
 *   OFFBOARDING    → TERMINATED | ACTIVE
 *   TERMINATED     → ARCHIVED | OFFBOARDING
 *
 * ARCHIVED is terminal — no further transitions allowed.
 * PII is pseudonymised on entry to ARCHIVED.
 *
 * `OFFBOARDING → ACTIVE` is the cancellation path: a departure that is called off returns
 * the employee to duty. Without it `OffboardingWorkflowService.cancelWorkflow` threw
 * LIFECYCLE_TRANSITION_INVALID and — being @Transactional — rolled the whole cancellation
 * back, so no offboarding file could ever be cancelled.
 */
public enum LifecycleStatus {
    PRE_ONBOARDING,
    ACTIVE,
    ON_LEAVE,
    ON_MISSION,
    OFFBOARDING,
    TERMINATED,
    ARCHIVED;

    public boolean canTransitionTo(LifecycleStatus next) {
        return switch (this) {
            case PRE_ONBOARDING -> next == ACTIVE;
            case ACTIVE         -> next == ON_LEAVE || next == ON_MISSION || next == OFFBOARDING;
            // Resigning while on leave or on mission is ordinary. Without these,
            // startOffboarding skipped its transition (it catches and warns), the profile
            // stayed ON_LEAVE, and validateWorkflow then threw on ON_LEAVE → TERMINATED —
            // rolling back, so the file could never be closed either.
            case ON_LEAVE       -> next == ACTIVE || next == OFFBOARDING;
            case ON_MISSION     -> next == ACTIVE || next == OFFBOARDING;
            case OFFBOARDING    -> next == TERMINATED || next == ACTIVE;
            // TERMINATED → OFFBOARDING is the reopen path. Without it, reopening a validated
            // offboarding left the workflow open while the profile stayed TERMINATED — and
            // validating again then failed with "Transition interdite: TERMINATED → TERMINATED",
            // so the file could never be closed. ARCHIVED remains one-way (PII is pseudonymised).
            case TERMINATED     -> next == ARCHIVED || next == OFFBOARDING;
            case ARCHIVED       -> false;
        };
    }
}
