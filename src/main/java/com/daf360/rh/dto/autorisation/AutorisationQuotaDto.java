package com.daf360.rh.dto.autorisation;

import lombok.Builder;
import lombok.Data;

/**
 * What the employee has left, and the rules that decide it — for the request form.
 *
 * THE FORM SHOWS THE SAME NUMBERS THE SERVER WILL ENFORCE, because both come from here. The
 * timesheet computed "remaining" against a hardcoded 240 in one method and refused against a
 * hardcoded 240 in another; two literals for one rule drift the first time anybody edits one.
 *
 * A null cap means NO LIMIT for that entity — not zero. The form hides the gauge rather than
 * drawing an empty one.
 */
@Data
@Builder
public class AutorisationQuotaDto {

    private Integer dailyCapMinutes;
    private Integer monthlyCapMinutes;
    private Integer maxPerDay;
    private Integer noticeDays;
    private Boolean requiresJustification;

    /** Committed this month — approved AND pending, as the check counts them. */
    private long monthlyUsedMinutes;
    /** Null when there is no monthly cap. */
    private Integer monthlyRemainingMinutes;
}
