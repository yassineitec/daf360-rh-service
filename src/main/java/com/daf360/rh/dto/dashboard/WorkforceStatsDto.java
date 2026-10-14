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
        double pctSeniors,
        /** Tranches d'âge de tout l'effectif (date_of_birth). */
        AgeBreakdown ages,
        /** Tranches d'âge des seuls ingénieurs + projeteurs (grades EN / BC). */
        AgeBreakdown techAges,
        /**
         * Tranches d'âge d'un sous-groupe, affichées au clic sur sa tuile dans la pop-up.
         * Clés : femmes, hommes (Effectif) ; ingenieurs, projeteurs, autres (Ingénieurs /
         * Projeteurs) ; juniors, confirmes, seniors (Ancienneté).
         */
        java.util.Map<String, AgeBreakdown> groupAges
) {
    /** {@code paysId}/{@code label} are null for profiles with no country set. */
    public record CountryHeadcount(Long paysId, String label, long count) {}

    /**
     * Âge en années COMPLÈTES : moins de 25, 25–34, 35–44, 45–54, 55 et plus.
     *
     * <p>Quand le groupe compte moins de {@code DashboardService.AGE_MIN_GROUP} personnes,
     * {@code hidden} est vrai et tous les compteurs valent 0 : sur un département de deux
     * personnes, la répartition dirait l'âge de chacun. Seul {@code total} reste, pour que
     * l'écran puisse expliquer pourquoi rien n'est affiché.
     */
    public record AgeBreakdown(
            long moins25,
            long de25a34,
            long de35a44,
            long de45a54,
            long plus55,
            /** Profils sans date de naissance — comptés dans le total, dans aucune tranche. */
            long nonRenseigne,
            long total,
            /** Âge moyen des profils datés, à une décimale ; null si aucun ou si masqué. */
            Double ageMoyen,
            boolean hidden) {

        public static final AgeBreakdown EMPTY = new AgeBreakdown(0, 0, 0, 0, 0, 0, 0, null, false);
    }
}
