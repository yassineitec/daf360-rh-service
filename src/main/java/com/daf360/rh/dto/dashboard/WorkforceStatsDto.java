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
        List<CountryHeadcount> byCountry,
        /** Code de grade EN1 … EN8. */
        long ingenieurs,
        /** Code de grade BC1 … BC5. */
        long projeteurs,
        /** Pourcentages sur ingenieurs + projeteurs (les autres profils ne comptent pas). */
        double pctIngenieurs,
        double pctProjeteurs,
        /** Ancienneté (hire_date) en années complètes : moins de 5 ans. */
        long juniors,
        /** 5 à 7 ans. */
        long confirmes,
        /** 8 ans et plus. */
        long seniors,
        /** Profils sans date d'embauche. */
        long ancienneteNonDefinie,
        double pctJuniors,
        double pctConfirmes,
        double pctSeniors
) {
    /** {@code paysId}/{@code label} are null for profiles with no country set. */
    public record CountryHeadcount(Long paysId, String label, long count) {}
}
