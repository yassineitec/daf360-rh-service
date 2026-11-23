package com.daf360.rh.dto.interview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The interviewer's PV: a summary of the interview, their opinion on the candidate and,
 * since V135, their answers to the interview type's questionnaire (empty when the type
 * has no question).
 */
public record SubmitInterviewReportRequest(
        @NotBlank(message = "Le résumé de l'entretien est obligatoire")
        @Size(min = 20, max = 4000, message = "Le résumé doit faire entre 20 et 4000 caractères")
        String summary,
        @NotNull(message = "L'avis sur le candidat est obligatoire")
        @Pattern(regexp = "FAVORABLE|RESERVED|UNFAVORABLE", message = "Avis invalide")
        String opinion,
        @Size(max = 200) List<@Valid InterviewAnswerRequest> answers
) {
    /** A PV without questionnaire — the V132 shape. */
    public SubmitInterviewReportRequest(String summary, String opinion) {
        this(summary, opinion, List.of());
    }
}
