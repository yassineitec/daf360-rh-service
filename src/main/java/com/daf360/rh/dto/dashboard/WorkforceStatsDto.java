package com.daf360.rh.dto.dashboard;

public record WorkforceStatsDto(
        long totalActifs,
        long hommes,
        long femmes,
        long nonDefini,
        double pctHommes,
        double pctFemmes
) {}
