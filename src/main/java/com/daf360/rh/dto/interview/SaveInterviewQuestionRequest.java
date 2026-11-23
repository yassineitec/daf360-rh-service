package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create / replace one question (V135). Fields that do not apply to the response type are
 * ignored (options for TEXT / RATING, the rating scale for TEXT / SELECT / MULTI_SELECT).
 * RATING defaults to 1 → 5, step 1, when the scale is left empty.
 */
public record SaveInterviewQuestionRequest(
        @NotBlank(message = "Le libellé de la question est obligatoire")
        @Size(max = 500, message = "Le libellé ne doit pas dépasser 500 caractères")
        String label,
        @Size(max = 500, message = "Le libellé anglais ne doit pas dépasser 500 caractères")
        String labelEn,
        @Size(max = 1000, message = "L'aide ne doit pas dépasser 1000 caractères")
        String helpText,
        @NotNull(message = "Le type de réponse est obligatoire")
        @Pattern(regexp = "TEXT|RATING|SELECT|MULTI_SELECT", message = "Type de réponse invalide")
        String responseType,
        @Size(max = 30, message = "30 options au maximum")
        List<@Size(max = 200, message = "Une option ne doit pas dépasser 200 caractères") String> options,
        Integer ratingMin,
        Integer ratingMax,
        Integer ratingStep,
        @Size(max = 100) String ratingMinLabel,
        @Size(max = 100) String ratingMaxLabel,
        Boolean required
) {}
