package com.daf360.rh.dto.profile;

import com.daf360.rh.domain.enums.LifecycleStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LifecycleTransitionDto {

    @NotNull(message = "Le nouveau statut est obligatoire")
    private LifecycleStatus newStatus;

    @NotBlank(message = "La raison de la transition est obligatoire")
    @Size(max = 500)
    private String reason;
}
