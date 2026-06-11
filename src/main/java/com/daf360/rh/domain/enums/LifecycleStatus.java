package com.daf360.rh.domain.enums;

/**
 * Employee profile lifecycle state machine.
 *
 * Allowed transitions:
 *   PRE_ONBOARDING → ACTIVE
 *   ACTIVE         → ON_LEAVE | ON_MISSION | OFFBOARDING
 *   ON_LEAVE       → ACTIVE
 *   ON_MISSION     → ACTIVE
 *   OFFBOARDING    → TERMINATED
 *   TERMINATED     → ARCHIVED
 *
 * ARCHIVED is terminal — no further transitions allowed.
 * PII is pseudonymised on entry to ARCHIVED.
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
            case ON_LEAVE       -> next == ACTIVE;
            case ON_MISSION     -> next == ACTIVE;
            case OFFBOARDING    -> next == TERMINATED;
            case TERMINATED     -> next == ARCHIVED;
            case ARCHIVED       -> false;
        };
    }
}
