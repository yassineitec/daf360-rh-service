package com.daf360.rh.dto.ref;

import lombok.Data;

/**
 * Body of PUT /api/hr/ref/grades/{id}/notice-period — V64.
 *
 * A null value clears the grade's default préavis. Deliberately allowed: an admin who
 * typed the wrong figure must be able to get back to "not configured", which the UI shows
 * as unset. Zero is a different, legitimate answer ("no préavis owed at this grade").
 */
@Data
public class UpdateGradeNoticePeriodRequest {
    /** Calendar days. Null clears the default; 0 means no préavis. Negative is rejected. */
    private Integer noticePeriodDays;
}
