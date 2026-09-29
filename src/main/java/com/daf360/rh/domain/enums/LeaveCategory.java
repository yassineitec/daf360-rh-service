package com.daf360.rh.domain.enums;

import java.math.BigDecimal;

/**
 * How much of a working day a {@link com.daf360.rh.domain.LeaveRequest} covers, and whether
 * it spans more than one day.
 *
 * Carried across from the timesheet's {@code CategoryAbsence}. The previous version of this
 * enum held only the three single-day values and was referenced by no code; without
 * {@link #MULTIPLE_DAYS} a request covering a range had no category to sit in, which is the
 * commonest case there is.
 *
 * The category drives the form as much as the data: MULTIPLE_DAYS is the only value that
 * reveals an end date, and the three single-day values fix the duration rather than letting
 * it be entered — see {@link #fixedDays()}.
 */
public enum LeaveCategory {

    /** A range. The only category where {@code dateFin} is meaningful and days are counted. */
    MULTIPLE_DAYS      ("Plusieurs jours",        "Multiple days",      null),

    FULL_DAY           ("Journée entière",        "Full day",           BigDecimal.ONE),
    HALF_DAY_MORNING   ("Demi-journée matin",     "Half day morning",   new BigDecimal("0.5")),
    HALF_DAY_AFTERNOON ("Demi-journée après-midi","Half day afternoon", new BigDecimal("0.5"));

    private final String labelFr;
    private final String labelEn;
    private final BigDecimal fixedDays;

    LeaveCategory(String labelFr, String labelEn, BigDecimal fixedDays) {
        this.labelFr = labelFr;
        this.labelEn = labelEn;
        this.fixedDays = fixedDays;
    }

    public String getLabelFr() {
        return labelFr;
    }

    public String getLabelEn() {
        return labelEn;
    }

    public String getLabel(String lang) {
        return "fr".equalsIgnoreCase(lang) ? labelFr : labelEn;
    }

    /**
     * The duration this category implies, or {@code null} for {@link #MULTIPLE_DAYS} where it
     * has to be computed from the date range, the weekend pattern and the holiday calendar.
     *
     * Returning null rather than zero on purpose: zero is a legitimate computed total (a range
     * falling entirely on weekends and holidays), so the two cases must stay distinguishable.
     */
    public BigDecimal fixedDays() {
        return fixedDays;
    }

    /** True when the request covers a date range rather than a single day. */
    public boolean isRange() {
        return this == MULTIPLE_DAYS;
    }
}
