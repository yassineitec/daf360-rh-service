package com.daf360.rh.dto.recruitment;

import java.util.Map;

/**
 * One line of a recruitment demand's history (« Historique » drawer of its detail page).
 *
 * @param at        ISO instant (UTC, with Z)
 * @param type      CREATED | REQUESTED_AGAIN | REQUESTED_LATER | APPROVED | REJECTED | CANCELLED |
 *                  HEADCOUNT_CHANGED | CLOSED_MANUAL | CLOSED_FILLED | REOPENED | REOPENED_AUTO |
 *                  CANDIDATE_ADDED | CANDIDATE_REUSED | OFFER_ACCEPTED | CANDIDATE_ARCHIVED |
 *                  CANDIDATE_REJECTED
 * @param actorName who did it; null for the system (automatic close / archive) or unknown
 * @param candidateId / candidateName  the candidature concerned, for candidate events
 * @param details   what the line needs to say (from / to, note, reason, other demand…)
 */
public record DemandHistoryEvent(
        String at,
        String type,
        String actorName,
        Long candidateId,
        String candidateName,
        Map<String, String> details
) {}
