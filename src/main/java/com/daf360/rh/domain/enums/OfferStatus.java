package com.daf360.rh.domain.enums;

/**
 * Lifecycle of one offer ROUND (see {@link com.daf360.rh.domain.JobOffer}).
 * Matches CK_JobOffer_Status in DAF360_HR.job_offers (V41, extended by V98).
 *
 * DRAFT     → costed and submitted to the finance approval queue, NOT yet with the
 *             candidate. Added by V98: a round now has to clear the budget before it can
 *             be extended, so there has to be a state where it exists and has not.
 * SENT      → offer extended to the candidate, awaiting their decision.
 * ACCEPTED  → candidate accepted; IT provisioning / hire may proceed.
 * REJECTED  → candidate declined (or offer withdrawn); {@code rejection_reason} set.
 * EXPIRED   → validity date passed with no decision.
 *
 * <p>Nothing sets EXPIRED today — no scheduler reads {@code expiry_date}. It is kept
 * because the CHECK constraint allows it and the frontend renders it.
 */
public enum OfferStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
    EXPIRED
}
