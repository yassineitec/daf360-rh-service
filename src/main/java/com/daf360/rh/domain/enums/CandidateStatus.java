package com.daf360.rh.domain.enums;

/**
 * Matches CK_Candidate_Status in DAF360_HR.candidates.
 * Allowed values verified from the live DB (2026-06-01).
 * OFFER_SENT added in V41 — the offer/negotiation stage between screening
 * acceptance and IT provisioning (see {@link OfferStatus}).
 */
public enum CandidateStatus {
    PENDING,
    ACCEPTED,
    OFFER_SENT,
    REJECTED,
    IT_IN_PROGRESS,
    EMAIL_RECEIVED,
    HR_IN_PROGRESS,
    HIRED,
    ARCHIVED
}
