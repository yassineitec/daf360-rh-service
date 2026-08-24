package com.daf360.rh.domain.enums;

/**
 * Lifecycle of a mission (see {@link com.daf360.rh.domain.Mission}).
 * Matches CK_Mission_Status in DAF360_HR.missions (V78).
 *
 * PENDING_HR       → planned by a manager, waiting for RH to price it (billeterie).
 * REJECTED_HR      → RH refused it; {@code hrNotes} says why. Terminal.
 * PENDING_FINANCE  → RH validated and priced it, waiting for the finance decision.
 * APPROVED         → finance approved. The ONLY status the employee's calendar shows.
 * REJECTED_FINANCE → finance refused the cost; {@code financeNotes} says why. Terminal.
 * CANCELLED        → called off (by the manager before validation, or through an
 *                    accepted employee cancellation request). Terminal.
 */
public enum MissionStatus {
    PENDING_HR,
    REJECTED_HR,
    PENDING_FINANCE,
    APPROVED,
    REJECTED_FINANCE,
    CANCELLED;

    /** Terminal statuses — nothing may be stamped on the mission any more. */
    public boolean isTerminal() {
        return this == REJECTED_HR || this == REJECTED_FINANCE || this == CANCELLED;
    }
}
