package com.daf360.rh.domain.enums;

/**
 * What the employee asks for on their own mission.
 * Matches CK_MissionChangeReq_Type in DAF360_HR.mission_change_requests (V78).
 *
 * PERIOD_CHANGE → move the dates; the requested window is carried on the request.
 * CANCELLATION  → call the mission off entirely.
 */
public enum MissionChangeRequestType {
    PERIOD_CHANGE,
    CANCELLATION
}
