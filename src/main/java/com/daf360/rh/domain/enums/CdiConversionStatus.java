package com.daf360.rh.domain.enums;

/**
 * Status of a CIVP → CDI conversion request (V119).
 *
 * <pre>
 * SUBMITTED ──validator──► APPROVED | REJECTED
 *     └──validator: escalate──► ESCALATED ──arbiter──► APPROVED | REJECTED
 * SUBMITTED | ESCALATED ──requester / RH──► CANCELLED
 * </pre>
 * APPROVED is a decision, not the conversion: RH carries it out afterwards from the
 * profile, which stamps {@code converted_contract_id} on the request.
 */
public enum CdiConversionStatus {
    SUBMITTED,
    ESCALATED,
    APPROVED,
    REJECTED,
    CANCELLED;

    public boolean isPending() {
        return this == SUBMITTED || this == ESCALATED;
    }
}
