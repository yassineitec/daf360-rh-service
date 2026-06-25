package com.daf360.rh.dto.dashboard;

public record DashboardStatsDto(
        long   totalActifs,
        long   newThisMonth,
        long   onLeave,
        long   pendingRequests,
        double pctFemmes,
        double pctHommes,
        long   collaborateursSansManager,
        long   contratsARenouveler
) {}
