package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Stage 2, left panel — the manager acknowledges the departure.
 *
 * The comment is optional: a manager who has nothing to add still has to be able to
 * validate, and forcing a sentence only produces "ok".
 */
@Data
public class ManagerValidationRequestDto {

    @Size(max = 1000)
    private String comment;
}
