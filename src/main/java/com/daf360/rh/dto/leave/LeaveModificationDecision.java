package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * One approver's answer, at either stage.
 *
 * The same shape serves the manager and HR: which stage is being decided follows from the
 * request's current state, not from anything the caller sends — a payload that named its own
 * stage would let a manager answer HR's question.
 */
@Data
public class LeaveModificationDecision {

    /** true = agree, false = refuse. */
    @NotNull
    private Boolean decision;

    /** Required on a refusal; the service rejects a blank one. */
    @Size(max = 2000)
    private String motifRefus;
}
