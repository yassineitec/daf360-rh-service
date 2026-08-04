package com.daf360.rh.dto.offboarding;

import lombok.Data;

/**
 * Ticks or unticks a checklist line, optionally attaching proof.
 *
 * Unticking is allowed on purpose — unlike an asset return, which cannot be un-confirmed
 * because the laptop is physically back, a checklist tick is a claim someone can get wrong.
 */
@Data
public class UpdateChecklistItemDto {

    private Boolean isDone;

    /** Proof for the line. Absent leaves whatever is there; the tick is the primary act. */
    private String documentUrl;
}
