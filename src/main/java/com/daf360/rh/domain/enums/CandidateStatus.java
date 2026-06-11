package com.daf360.rh.domain.enums;

/**
 * Matches CK_Candidate_Status in DAF360_HR.candidates.
 * Allowed values verified from the live DB (2026-06-01).
 */
public enum CandidateStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    IT_IN_PROGRESS,
    EMAIL_RECEIVED,
    HR_IN_PROGRESS,
    HIRED,
    ARCHIVED
}
