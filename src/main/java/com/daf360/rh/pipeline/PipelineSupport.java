package com.daf360.rh.pipeline;

import com.daf360.rh.domain.enums.CandidateStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for pipeline-derived, presentation-oriented values that
 * are shared between the Kanban ({@code PipelineService}) and the candidate list
 * ({@code CandidateMapper}). Keeps status→stage mapping, the fit-score heuristic
 * and workflow progress consistent across both endpoints.
 */
public final class PipelineSupport {

    private PipelineSupport() {}

    /** Number of days before {@code expected_start_date} at which a candidate is flagged urgent. */
    public static final int URGENT_WINDOW_DAYS = 14;

    // ── Canonical status → stage ────────────────────────────────────────────────

    private static final Map<String, String> STATUS_TO_STAGE = new LinkedHashMap<>() {{
        put("PENDING",        "SCREENING");
        put("ACCEPTED",       "ENTRETIEN");
        put("HR_IN_PROGRESS", "ENTRETIEN");
        put("OFFER_SENT",     "OFFRE");
        put("IT_IN_PROGRESS", "OFFRE");
        put("EMAIL_RECEIVED", "OFFRE");
        put("HIRED",          "RECRUTE");
        put("REJECTED",       "REJETE");
        put("ARCHIVED",       "REJETE");
    }};

    private static final Map<String, String> STAGE_LABELS = new LinkedHashMap<>() {{
        put("SCREENING", "Candidatures");
        put("ENTRETIEN", "Entretiens");
        put("OFFRE",     "Offres");
        put("RECRUTE",   "Recrutés");
        put("REJETE",    "Rejetés");
    }};

    public static String stageKey(String status) {
        return STATUS_TO_STAGE.getOrDefault(status, "SCREENING");
    }

    public static String stageLabelForKey(String stageKey) {
        return STAGE_LABELS.getOrDefault(stageKey, stageKey);
    }

    public static String stageLabel(String status) {
        return stageLabelForKey(stageKey(status));
    }

    public static String stageLabel(CandidateStatus status) {
        return status == null ? stageLabelForKey("SCREENING") : stageLabel(status.name());
    }

    // ── Fit-score heuristic ─────────────────────────────────────────────────────

    /**
     * Deterministic 0–100 fit score derived only from cheaply-available data so that
     * the Kanban and the candidate list produce identical values.
     * Base is driven by how far the candidate has progressed; a present CV and years
     * of experience add small bonuses.
     */
    public static int fitScore(String status, boolean hasCv, Integer experienceYears) {
        int base = switch (status == null ? "" : status) {
            case "PENDING"        -> 45;
            case "ACCEPTED"       -> 60;
            case "HR_IN_PROGRESS" -> 68;
            case "OFFER_SENT"     -> 80;
            case "IT_IN_PROGRESS" -> 74;
            case "EMAIL_RECEIVED" -> 85;
            case "HIRED"          -> 100;
            case "REJECTED", "ARCHIVED" -> 30;
            default               -> 50;
        };
        int score = base;
        if (hasCv) score += 5;
        if (experienceYears != null) score += Math.min(experienceYears, 10) / 2; // up to +5
        return Math.max(0, Math.min(100, score));
    }

    // ── Workflow progress (%) ───────────────────────────────────────────────────

    /**
     * Progress of the workflow relevant to the candidate's current phase:
     * IT provisioning while in the OFFRE phase, HR onboarding once accepted/hired.
     * Returns {@code null} when neither workflow applies to the phase.
     */
    public static Integer progressPercent(String status, String provStatus, Boolean onboardingCompleted) {
        if ("IT_IN_PROGRESS".equals(status) || "EMAIL_RECEIVED".equals(status)) {
            return switch (provStatus == null ? "" : provStatus) {
                case "PENDING"       -> 15;
                case "IN_PROGRESS"   -> 50;
                case "EMAIL_CREATED" -> 75;
                case "COMPLETED"     -> 100;
                default              -> 0;
            };
        }
        if ("HR_IN_PROGRESS".equals(status) || "HIRED".equals(status)) {
            return Boolean.TRUE.equals(onboardingCompleted) ? 100 : 50;
        }
        return null;
    }
}
