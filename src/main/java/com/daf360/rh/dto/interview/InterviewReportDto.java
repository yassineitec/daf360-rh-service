package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;

/** One interviewer's PV (V132). */
public record InterviewReportDto(
        Long id,
        Long authorUserId,
        String authorName,
        String summary,
        /** FAVORABLE / RESERVED / UNFAVORABLE. */
        String opinion,
        OffsetDateTime submittedAt,
        OffsetDateTime updatedAt
) {}
