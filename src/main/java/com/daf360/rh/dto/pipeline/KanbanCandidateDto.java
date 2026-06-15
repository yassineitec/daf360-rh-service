package com.daf360.rh.dto.pipeline;

import java.util.List;

public record KanbanCandidateDto(
        Long id,
        String fullName,
        String photoUrl,
        String poste,
        int fitScore,
        String badge,
        String badgeType,
        String experience,
        String location,
        List<String> skills,
        String note,
        String nextEvent,
        String salary,
        boolean isUrgent,
        String stage
) {}
