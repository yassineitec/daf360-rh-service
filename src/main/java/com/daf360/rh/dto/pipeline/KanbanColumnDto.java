package com.daf360.rh.dto.pipeline;

import java.util.List;

public record KanbanColumnDto(
        String stage,
        String stageLabel,
        int count,
        List<KanbanCandidateDto> candidates
) {}
