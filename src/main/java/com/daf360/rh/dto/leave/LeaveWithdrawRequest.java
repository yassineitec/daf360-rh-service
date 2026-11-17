package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Why the employee is taking their own pending request back.
 *
 * The motive is REQUIRED, like the one an approver must give to refuse. A congé that vanishes
 * from a manager's queue with no explanation is the same problem from the other direction —
 * and the days are not the only thing a withdrawal affects: somebody may already have planned
 * around the absence.
 */
@Data
public class LeaveWithdrawRequest {

    @NotBlank
    @Size(max = 2000)
    private String motif;
}
