package com.daf360.rh.dto.candidate;

import lombok.Builder;
import lombok.Data;

/**
 * KPI row on the /candidates dashboard. Mirrors the three design tiles:
 * Total Candidats · Délai Recrutement Moyen · Postes Urgents.
 * Delta fields are nullable — the frontend hides the sub-label when there is
 * not enough history to compute a meaningful comparison.
 */
@Data
@Builder
public class CandidateDashboardStats {

    /** Total candidate count (tenant-scoped). */
    private long totalCandidates;

    /** Growth of candidates created in the last 30 days vs the previous 30 days, in %. */
    private Double monthGrowthPct;

    /** All-time average days from creation to acceptance (accepted/hired candidates). */
    private Double avgRecruitmentDays;

    /** Recent-vs-previous 90-day delta of the average recruitment delay (negative = faster). */
    private Double avgRecruitmentDaysDelta;

    /** Open positions requiring HR action — pending recruitment demands (EN_ATTENTE). */
    private long urgentPositions;

    // ── Funnel-health KPIs (Pipeline RH page) ───────────────────────────────────
    /** Candidates still moving through the pipeline (not hired, rejected or archived). */
    private long activeCandidates;

    /** Total candidates recruited (status HIRED). */
    private long hiredTotal;

    /** Offer acceptance rate (%) among decided offers; null when no offer decided yet. */
    private Double offerAcceptanceRate;
}
