package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransitionRequest {

    @NotBlank
    private String newStatus;

    @NotBlank
    private String actionCode;

    private String commentaire;
    private String documentReference;
    private String endReasonCode;
}
