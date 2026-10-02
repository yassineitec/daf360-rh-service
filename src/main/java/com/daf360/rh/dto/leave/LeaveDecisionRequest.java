package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Approve or refuse.
 *
 * {@code motifRefus} is required on a refusal and ignored on an approval — enforced in the
 * service rather than by bean validation, because one DTO carries both paths and a
 * {@code @NotBlank} would also fire on the approval.
 *
 * {@code approved} is boxed and {@code @NotNull}: the timesheet accepted a null decision and
 * threw IllegalArgumentException deep inside the transaction. A missing field is a 400.
 */
@Data
public class LeaveDecisionRequest {

    @NotNull
    private Boolean approved;

    @Size(max = 2000)
    private String motifRefus;
}
