package com.daf360.rh.dto.teletravail;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One entity's télétravail rules, for the administration screen.
 *
 * {@code allowedDurations} is the half-day option: BOTH (full or half days), FULL_DAY (whole
 * days only, as the timesheet did) or HALF_DAY (half days only). The caps are nullable and
 * null means NO LIMIT — the same convention as the autorisation rules beside it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeletravailRulesDto {

    private Long paysId;
    private String frenchLabel;
    private String englishLabel;
    private String isoCode;
    private long employees;

    /** BOTH | FULL_DAY | HALF_DAY. */
    private String allowedDurations;
    private BigDecimal maxDaysPerWeek;
    private BigDecimal maxDaysPerMonth;
    private Integer noticeDays;
    /** Approving a request debits soldeTeletravail; archiving an approved one refunds it. */
    private Boolean usesBalance;
}
