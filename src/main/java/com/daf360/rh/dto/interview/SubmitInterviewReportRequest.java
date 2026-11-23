package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The interviewer's PV: a summary of the interview and their opinion on the candidate. */
public record SubmitInterviewReportRequest(
        @NotBlank(message = "Le résumé de l'entretien est obligatoire")
        @Size(min = 20, max = 4000, message = "Le résumé doit faire entre 20 et 4000 caractères")
        String summary,
        @NotNull(message = "L'avis sur le candidat est obligatoire")
        @Pattern(regexp = "FAVORABLE|RESERVED|UNFAVORABLE", message = "Avis invalide")
        String opinion
) {}
