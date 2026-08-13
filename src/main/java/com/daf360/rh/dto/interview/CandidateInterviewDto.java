package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code interviewerUserId}/{@code interviewerName} are the lead (first) interviewer,
 * kept so older consumers keep rendering; {@code interviewers} is the full panel.
 */
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
        List<UserPickerDto> interviewers,
        String status,
        String result,
        Integer sequenceNumber,
        OffsetDateTime createdAt
) {}
