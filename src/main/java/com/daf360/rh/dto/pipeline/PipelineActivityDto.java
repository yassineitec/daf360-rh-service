package com.daf360.rh.dto.pipeline;

public record PipelineActivityDto(
        Long id,
        Long candidateId,
        String candidateName,
        String action,
        String actionLabel,
        String stage,
        String timestamp,
        String performedBy
) {}
