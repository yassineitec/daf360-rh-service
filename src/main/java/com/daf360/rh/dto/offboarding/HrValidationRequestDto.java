package com.daf360.rh.dto.offboarding;

import lombok.Data;

import java.time.LocalDate;

/**
 * Stage 2, right panel — RH validates and adjusts.
 *
 * The adjustment is the point of this panel: the declaration records the date the two
 * sides negotiated, and RH confirms it or corrects it here. Both fields are optional —
 * `null` means "leave as declared", so validating without changing anything is an empty
 * body rather than a round-trip of the current values.
 */
@Data
public class HrValidationRequestDto {

    /** Overrides the declared departure date. Re-dates the pending asset returns with it. */
    private LocalDate lastWorkingDay;

    /** Notice paid but not served — feeds the solde de tout compte. */
    private Boolean noticePaidNotWorked;
}
