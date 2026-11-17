package com.daf360.rh.dto.teletravail;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The approver's answer. A refusal without a motive is refused by the service. */
@Data
public class TeletravailDecision {

    @NotNull
    private Boolean approved;

    @Size(max = 2000)
    private String motifRefus;
}
