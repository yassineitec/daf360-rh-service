package com.daf360.rh.dto.help;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create / update body of an admin help question. French is required; English and Arabic
 * are optional — a blank value is stored as null and the screen falls back to French.
 */
public record SaveHelpFaqRequest(
        @NotBlank @Size(max = 80)   String pageKey,
        @NotBlank @Size(max = 300)  String questionFr,
        @NotBlank @Size(max = 2000) String answerFr,
        @Size(max = 300)  String questionEn,
        @Size(max = 2000) String answerEn,
        @Size(max = 300)  String questionAr,
        @Size(max = 2000) String answerAr,
        Integer orderIndex,
        Boolean isActive
) {}
