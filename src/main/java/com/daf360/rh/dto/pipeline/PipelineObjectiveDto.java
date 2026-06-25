package com.daf360.rh.dto.pipeline;

public record PipelineObjectiveDto(
        String month,
        String monthLabel,
        int target,
        int actual
) {}
