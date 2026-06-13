package com.daf360.rh.dto.dashboard;

public record DashboardStatsDto(
        long collaborateursSansManager,
        long contratsARenouveler,
        long totalActifs,
        long entretiens,   // 0 — no interview domain yet
        long formations    // 0 — no training domain yet
) {}
