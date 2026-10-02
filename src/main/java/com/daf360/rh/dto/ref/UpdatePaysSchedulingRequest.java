package com.daf360.rh.dto.ref;

import lombok.Data;

import java.util.List;

/**
 * Body of {@code PUT /api/hr/ref/pays/{id}/scheduling} — an entity's rest days, and nothing else.
 *
 * The weekend is sent WHOLE and replaces what is stored: there is no add/remove, because the
 * join carries nothing but the pair and a replace cannot drift out of step with a diff.
 *
 * THE TWO LEAVE DELAYS USED TO LIVE HERE AND NO LONGER DO. `advance_notice_days` and
 * `leave_gap_days` are configured per LEAVE TYPE (V109) — annual leave is planned weeks ahead,
 * sick leave is declared the morning it happens, and one value per country could express only
 * one of those. The country columns survive as the fallback a type inherits when it sets
 * nothing; they simply have no editor, because editing them in two places was the confusion.
 */
@Data
public class UpdatePaysSchedulingRequest {

    /** DayOfWeek names (MONDAY…SUNDAY). At least one, fewer than seven — see the service. */
    private List<String> weekendDays;
}
