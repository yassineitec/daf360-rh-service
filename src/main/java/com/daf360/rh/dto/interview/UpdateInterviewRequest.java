package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record UpdateInterviewRequest(
        OffsetDateTime scheduledAt,
        @Size(max = 255) String location,
        @Size(max = 1000) String interviewerNotes,
        Long interviewerUserId,
        String status,
        String result
) {}
