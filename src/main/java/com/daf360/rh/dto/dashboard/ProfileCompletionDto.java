package com.daf360.rh.dto.dashboard;

public record ProfileCompletionDto(
        double tauxGlobalPct,
        long dossiersComplets,
        long dossiersIncomplets
) {}
