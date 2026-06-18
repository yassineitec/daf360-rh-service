package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ValidateTrialRequest {

    @NotNull
    private Boolean approved;

    private String commentaire;
}
