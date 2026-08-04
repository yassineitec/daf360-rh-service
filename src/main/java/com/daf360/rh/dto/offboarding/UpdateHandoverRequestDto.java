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
}
