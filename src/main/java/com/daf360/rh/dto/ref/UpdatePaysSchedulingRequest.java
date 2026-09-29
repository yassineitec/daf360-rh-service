package com.daf360.rh.dto.ref;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/**
 * Body of {@code PUT /api/hr/ref/pays/{id}/scheduling}.
 *
 * The weekend is sent WHOLE and replaces what is stored — there is no add/remove, because the
 * join carries nothing but the pair and a replace cannot drift out of step with a diff.
 *
 * Null on either delay clears it, which is how a country says "no default, let each leave type
 * decide". That is different from 0, which would mean "no delay" — the service and the screen
 * both keep the two apart.
 */
@Data
public class UpdatePaysSchedulingRequest {

    /** DayOfWeek names (MONDAY…SUNDAY). At least one, fewer than seven — see the service. */
    private List<String> weekendDays;

    @Min(value = 0, message = "Le préavis ne peut pas être négatif")
    private Integer advanceNoticeDays;

    @Min(value = 0, message = "Le délai entre deux congés ne peut pas être négatif")
    private Integer leaveGapDays;
}
