package com.daf360.rh.dto.pipeline;

import java.util.List;

public record KanbanCandidateDto(
        Long id,
        String fullName,
        String initials,
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
        String stage,
        String stageLabel,
        String applicationDate,
        String email,
        String status
) {}
