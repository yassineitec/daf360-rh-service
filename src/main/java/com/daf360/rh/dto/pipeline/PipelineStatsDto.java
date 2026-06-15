package com.daf360.rh.dto.pipeline;

public record PipelineStatsDto(
        long totalCandidats,
        long enEntretien,
        double scoreMoyen,
        long recrutementsClos,
        double delaiMoyenJours,
        long postesUrgents
) {}
