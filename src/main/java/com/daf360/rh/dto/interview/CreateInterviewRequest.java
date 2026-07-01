package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateInterviewRequest(
        @NotNull Long interviewTypeId,
        @NotNull OffsetDateTime scheduledAt,
        @Size(max = 255) String location,
        @Size(max = 1000) String interviewerNotes,
        Long interviewerUserId
) {}
