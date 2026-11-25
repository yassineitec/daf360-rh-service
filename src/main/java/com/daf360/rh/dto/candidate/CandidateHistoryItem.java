package com.daf360.rh.dto.candidate;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * One line of the recruitment history — an action, when it happened and who did it.
 * Built by {@link com.daf360.rh.service.CandidateHistoryService} from the action log and the
 * candidatures' own tables (interviews, PVs, finance decisions).
 */
@Data
@Builder
public class CandidateHistoryItem {
    /** Unique across sources (`log-12`, `itv-4-planned`, `pv-7`, `fin-3-decided`). */
    private String id;
    /** ISO-8601 instant, UTC. */
    private String timestamp;
    /** CANDIDATE | INTERVIEW | OFFER | FINANCE | ONBOARDING — the column the screen groups by. */
    private String category;
    /** Action code — the screen translates it (CANDIDATES.HISTORY.ACTION.*). */
    private String action;
    /** French label, the fallback when the screen has no translation for {@link #action}. */
    private String actionLabel;
    private Long   performedByUserId;
    /** Null when the actor is unknown or the system acted. */
    private String performedByName;
    /** Free text the actor typed with the action (reason, validation comment, finance note). */
    private String comment;
    /**
     * The CANDIDATE's status after the action — only on candidature-level actions. The
     * candidate page's rail reads it to know how far a closed candidature went.
     */
    private String resultingStatus;
    /**
     * Outcome of the action, one namespace across every source — what the « Statut » column
     * shows and filters on (candidate statuses, PLANNED / PV_FILED / PASS / FAIL / CANCELLED,
     * OFFER_DRAFT, FINANCE_PENDING / FINANCE_APPROVED / FINANCE_REJECTED, IT_*…). Null when
     * the action has no outcome of its own (a profile edit, a CV upload).
     */
    private String status;
    /** Facts of the action (round, proposedSalary, interviewType, email…), shown as detail. */
    private Map<String, String> details;

    // ── Global history only (null on a single candidate's history) ──
    private Long   candidateId;
    private String candidateName;
    private String candidatePosition;
    /** For the avatar; null until the candidate has an employee profile. */
    private String candidateGender;
}
