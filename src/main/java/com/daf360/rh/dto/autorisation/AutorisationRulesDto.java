package com.daf360.rh.dto.autorisation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entity's autorisation rules, for the administration screen.
 *
 * The caps are NULLABLE and null means NO LIMIT, not zero. An entity that does not cap
 * autorisations is a real configuration; one capped at zero minutes would be a mistake
 * indistinguishable from an unset value — so the form offers "aucune limite" explicitly
 * rather than leaving an empty box to be guessed at.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutorisationRulesDto {

    private Long paysId;
    private String frenchLabel;
    private String englishLabel;
    private String isoCode;
    /** How many active people the rules apply to — the figure that says whether it matters. */
    private long employees;

    private Integer dailyCapMinutes;
    private Integer monthlyCapMinutes;
    private Integer maxPerDay;
    private Integer noticeDays;
    private Boolean requiresJustification;
}
