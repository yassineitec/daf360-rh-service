package com.daf360.rh.history;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * One line of a module's « Historique » tab (Onboarding, Passage en CDI, Offboarding): an
 * action, when it happened, who took it, and which file it is about. Every module returns
 * this same shape, so the screens share one history table.
 */
@Data
@Builder
public class HistoryEntryDto {
    /** Unique within the response ({@code log-12}, {@code step-4}…). */
    private String id;
    /** ISO-8601 instant, UTC. */
    private String timestamp;
    /** The part of the file the action touched — the « Type » column (module-specific codes). */
    private String category;
    /** Action code — the screen translates it with the module's own labels. */
    private String action;
    private Long   actorId;
    /** Null when the actor is unknown or the system acted. */
    private String actorName;
    /** Outcome of the action — the « Statut » column and filter; null when it has none. */
    private String status;
    /** Free text the actor typed with the action (reason, comment). */
    private String comment;
    /** Facts of the action (dates, amounts, labels), shown as detail. */
    private Map<String, String> details;

    // ── The file the line is about ──
    /** Id the screen opens (offboarding file, CDI request, candidate in onboarding). */
    private Long   subjectId;
    private String subjectName;
    /** Second line under the name (position, departure type…). */
    private String subjectSubtitle;
    /** Employee profile behind the subject, for the avatar; null for a candidate. */
    private Long   subjectProfileId;
    private String subjectPhotoUrl;
    private String subjectGender;
}
