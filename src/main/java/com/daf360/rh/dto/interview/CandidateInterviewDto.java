package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;

public record CandidateInterviewDto(
        Long id,
        Long candidateId,
        Long interviewTypeId,
        String interviewTypeName,
        OffsetDateTime scheduledAt,
        String location,
        String interviewerNotes,
        Long interviewerUserId,
        String interviewerName,
        String status,
        String result,
        Integer sequenceNumber,
        OffsetDateTime createdAt
) {}
