package com.daf360.rh.dto.dashboard;

import java.util.List;

public record WorkforceStatsDto(
        long totalActifs,
        long hommes,
        long femmes,
        long nonDefini,
        double pctHommes,
        double pctFemmes,
        /** Active headcount per country, biggest first. Feeds the dashboard's bar chart. */
        List<CountryHeadcount> byCountry
) {
    /** {@code paysId}/{@code label} are null for profiles with no country set. */
    public record CountryHeadcount(Long paysId, String label, long count) {}
}
