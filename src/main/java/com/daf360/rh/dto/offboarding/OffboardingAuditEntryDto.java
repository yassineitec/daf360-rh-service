package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * One line of a file's audit trail.
 *
 * The drawer used to rebuild this client-side from whatever timestamps happened to be on the
 * instance DTO — so it could only ever show the handful of events that left a visible field
 * behind, and attributed them to nobody. Every mutation has always called `AuditService.log`;
 * this is the shape that finally exposes it.
 */
@Data
@Builder
public class OffboardingAuditEntryDto {

    private OffsetDateTime timestamp;
    private String         action;
    private String         entityType;
    private String         entityId;
    /** Resolved display name; falls back to the raw id when the user is gone. */
    private String         actorName;
    private String         oldValue;
    private String         newValue;
}
