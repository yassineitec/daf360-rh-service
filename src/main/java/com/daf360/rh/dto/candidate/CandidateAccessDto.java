package com.daf360.rh.dto.candidate;

/**
 * GET /api/hr/candidates/{id}/access (V132).
 *
 * @param fullAccess  the caller reads the whole record (HR_ONBOARDING or IT_PROVISIONING)
 * @param interviewer the caller sits on one of this candidate's (non-cancelled) panels
 */
public record CandidateAccessDto(boolean fullAccess, boolean interviewer) {}
