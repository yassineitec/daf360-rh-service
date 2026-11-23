package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;
import java.util.List;

/** One interviewer's PV (V132), with their questionnaire answers (V135, in question order). */
public record InterviewReportDto(
        Long id,
        Long authorUserId,
        String authorName,
        String summary,
        /** FAVORABLE / RESERVED / UNFAVORABLE. */
        String opinion,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt,
        List<InterviewAnswerDto> answers
) {}
