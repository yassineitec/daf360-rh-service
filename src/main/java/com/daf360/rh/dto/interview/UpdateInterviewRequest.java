package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** Every field is optional: {@code null} means "leave unchanged". */
public record UpdateInterviewRequest(
        Long interviewTypeId,
        OffsetDateTime scheduledAt,
        @Size(max = 255) String location,
        @Size(max = 1000) String interviewerNotes,
        /** Replaces the whole panel when present; {@code []} clears it. */
        @Size(max = 20) List<Long> interviewerUserIds,
        String status,
        String result
) {}
