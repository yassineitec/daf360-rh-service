package com.daf360.rh.dto.recruitment;

import java.time.OffsetDateTime;

/**
 * One post of the « Bibliothèque des postes » (self-service, Encadrement section): a position
 * the entity already recruited for, with every value of its most recent demand so a manager
 * can raise the same need again in one click.
 *
 * @param demand          the most recent approved / closed demand for this post — the values
 *                        the new request is prefilled from
 * @param timesRequested  how many past demands named this same post (department + titles)
 * @param lastRequestedAt when the most recent one was submitted
 * @param departmentLabelEn the department's English label, for grouping in English
 */
public record JobLibraryEntry(
        RecruitmentDemandResponse demand,
        int timesRequested,
        OffsetDateTime lastRequestedAt,
        String departmentLabelEn
) {}
