package com.daf360.rh.domain.enums;

/**
 * RH's answer to a {@link MissionChangeRequestType}.
 * Matches CK_MissionChangeReq_Status in DAF360_HR.mission_change_requests (V78).
 */
public enum MissionChangeRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
