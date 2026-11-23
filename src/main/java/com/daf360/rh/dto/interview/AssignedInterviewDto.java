package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;

/**
 * One interview the caller sits on — the « Mes entretiens » list (V132). {@code myReportAt}
 * is null until the caller has written their PV, which is what the list sorts its « PV à
 * rédiger » rows on.
 */
public record AssignedInterviewDto(
        Long id,
        Long candidateId,
        String candidateName,
        String appliedPosition,
        String interviewTypeName,
        String interviewTypeNameEn,
        OffsetDateTime scheduledAt,
        String location,
        String status,
        String result,
        OffsetDateTime myReportAt,
        String graphJoinUrl
) {}
