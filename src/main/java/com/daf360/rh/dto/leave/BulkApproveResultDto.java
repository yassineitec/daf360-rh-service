package com.daf360.rh.dto.leave;

import java.util.List;

/**
 * Outcome of approving a queue in one action.
 *
 * Partial success is the normal case, not the exception: a bulk approve stops on no single
 * failure, because one employee's exhausted balance must not block the other nineteen
 * decisions. Each failure is reported with the id and the reason so the manager can see
 * exactly what did not go through.
 */
public record BulkApproveResultDto(
        int approved,
        int failed,
        List<Failure> failures
) {
    public record Failure(Long leaveRequestId, String employee, String reason) {}
}
