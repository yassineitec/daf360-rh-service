package com.daf360.rh.dto.teletravail;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the request form needs to render the rules it will be judged by.
 *
 * THE FORM SHOWS THE SAME NUMBERS THE SERVER ENFORCES, because both come from
 * {@code TeletravailService.rulesFor}. Usage is computed for the week and month of
 * {@code referenceDate} — the day the employee picked, or today before they pick one.
 *
 * A null cap means NO LIMIT, not zero; the form hides the line rather than showing "0".
 */
@Data
@Builder
public class TeletravailQuotaDto {

    /** FULL_DAY | HALF_DAY_MORNING | HALF_DAY_AFTERNOON — what this entity lets employees ask for. */
    private List<String> allowedCategories;
    private BigDecimal maxDaysPerWeek;
    private BigDecimal maxDaysPerMonth;
    private Integer noticeDays;
    private boolean usesBalance;

    /** soldeTeletravail as stored. Null = never recorded, which is not the same as 0. */
    private Double balance;
    /** Days already asked for and not yet decided — the balance still has to absorb them. */
    private BigDecimal pendingDays;

    private LocalDate referenceDate;
    private BigDecimal weekUsedDays;
    private BigDecimal monthUsedDays;
}
