package com.daf360.rh.dto.offboarding;

import lombok.Data;

/**
 * Stage 3 — Passation: who takes the work over, and the PV that closes the handover.
 *
 * Separate from the declaration PATCH, which carries the same `handoverManagerProfileId`
 * but is RH-only. Naming the successor is the *manager's* act and belongs to stage 3, and
 * before this the field could only be set in the optional search on the "Démarrer un
 * offboarding" modal — so a file opened from a profile page had no successor at all, and
 * no screen could give it one.
 */
@Data
public class UpdateHandoverRequestDto {

    /** Also moves the KNOWLEDGE_TRANSFER task's owner to that person. */
    private Long handoverManagerProfileId;

    private String handoverMinutesUrl;
    private String handoverMinutesName;

    /**
     * V65 — the manager-set start of the passation. The window ends on the last working day,
     * and the duration is derived from it rather than typed.
     */
    private java.time.LocalDate handoverStartedAt;

    /**
     * V65 — the PV written here instead of (or alongside) an uploaded file. Unlike the other
     * fields, a blank string clears it: a text area is how you delete what you wrote.
     */
    private String handoverMinutesText;
}
