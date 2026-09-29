package com.daf360.rh.dto.candidate;

import java.time.LocalDate;

/**
 * Optional filters of the recruitment list ({@code GET /api/hr/candidates}), on top of
 * status / stage / search. Every field is optional; {@code spontaneous} keeps only the
 * candidatures that answer no recruitment demand and wins over {@code demandId}.
 * {@code createdFrom} / {@code createdTo} are both inclusive calendar days, read in the
 * entity's timezone.
 */
public record CandidateListFilter(
        Long departmentId,
        Long demandId,
        boolean spontaneous,
        LocalDate createdFrom,
        LocalDate createdTo) {
}
