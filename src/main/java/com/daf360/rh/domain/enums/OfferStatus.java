package com.daf360.rh.domain.enums;

/**
 * Lifecycle of a job offer (see {@link com.daf360.rh.domain.JobOffer}).
 * Matches CK_JobOffer_Status in DAF360_HR.job_offers (V41).
 *
 * SENT      → offer extended to the candidate, awaiting their decision.
 * ACCEPTED  → candidate accepted; IT provisioning / hire may proceed.
 * REJECTED  → candidate declined (or offer withdrawn); {@code rejection_reason} set.
 * EXPIRED   → validity date passed with no decision.
 */
public enum OfferStatus {
    SENT,
    ACCEPTED,
    REJECTED,
    EXPIRED
}
