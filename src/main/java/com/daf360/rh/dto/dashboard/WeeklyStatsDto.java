package com.daf360.rh.dto.dashboard;

public record WeeklyStatsDto(
        long pointageEnAttente,
        double tauxAffectation,
        String weekLabel
) {}
