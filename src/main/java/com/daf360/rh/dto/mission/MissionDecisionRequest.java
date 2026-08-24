package com.daf360.rh.dto.mission;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A validation or a refusal, RH side and finance side alike. The notes are optional on an
 * approval and required on a refusal — enforced in the service, since the same DTO carries
 * both and a bean-validation NotBlank would also fire on the approval path.
 */
@Data
public class MissionDecisionRequest {

    @Size(max = 1000)
    private String notes;
}
