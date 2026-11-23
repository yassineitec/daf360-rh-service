package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * RH's decision on an interview after reading the PVs (V132). A FAIL needs a comment — it
 * ends up blocking the candidature, and the record must say why.
 */
public record ValidateInterviewRequest(
        @NotNull(message = "La décision est obligatoire")
        @Pattern(regexp = "PASS|FAIL", message = "Décision invalide")
        String decision,
        @Size(max = 1000) String comment
) {}
