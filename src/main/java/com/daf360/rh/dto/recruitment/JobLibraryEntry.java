package com.daf360.rh.dto.recruitment;

import java.time.OffsetDateTime;

/**
 * One post of the « Bibliothèque des postes » (self-service, Encadrement section): a position
 * the entity already recruited for, with every value of its most recent demand so a manager
 * can raise the same need again in one click.
 *
 * @param demand          the most recent approved / closed demand for this post — the values
 *                        the new request is prefilled from (its statut, headcount and
 *                        positionsFilled say where that demand stands)
 * @param timesRequested  how many past demands named this same post (department + titles)
 * @param lastRequestedAt when the most recent one was submitted
 * @param departmentLabelEn the department's English label, for grouping in English
 * @param totalPositionsFilled posts filled across every demand of this post (V137)
 * @param openDemandId    a demand of this post still APPROVED with posts left, or null — the
 *                        library warns before a manager files a duplicate of it (V137)
 * @param openRemaining   posts still to fill on that open demand
 */
public record JobLibraryEntry(
        RecruitmentDemandResponse demand,
        int timesRequested,
        OffsetDateTime lastRequestedAt,
        String departmentLabelEn,
        int totalPositionsFilled,
        Long openDemandId,
        int openRemaining
) {}
