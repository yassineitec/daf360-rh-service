package com.daf360.rh.dto.interview;

import java.time.OffsetDateTime;

/**
 * A calendar-ready view of an interview assigned to the current user (as interviewer).
 * {@code title} bundles the interview type + candidate name; {@code scheduledAt}
 * carries the exact hour so the shell home calendar can display both.
 */
public record MyInterviewEventDto(
        Long id,
        Long candidateId,
        String candidateName,
        String poste,
        OffsetDateTime scheduledAt,
        String location,
        String title
) {}
