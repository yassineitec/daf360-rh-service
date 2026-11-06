package com.daf360.rh.dto.help;

import java.time.OffsetDateTime;

/** One admin-typed help question, as read by the admin card, the assistant and the help page. */
public record HelpFaqDto(
        Long id,
        String pageKey,
        String questionFr,
        String answerFr,
        String questionEn,
        String answerEn,
        String questionAr,
        String answerAr,
        Integer orderIndex,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
