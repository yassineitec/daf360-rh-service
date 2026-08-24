package com.daf360.rh.dto.mission;

import com.daf360.rh.domain.enums.MissionChangeRequestStatus;
import com.daf360.rh.domain.enums.MissionChangeRequestType;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class MissionChangeRequestDto {

    private Long id;
    private Long missionId;

    /** Denormalised so the RH queue can render a row without loading the mission. */
    private String missionTitle;
    private String employeeName;

    private Long   requestedBy;
    private String requestedByName;

    private MissionChangeRequestType requestType;
    private LocalDate requestedStartDate;
    private LocalDate requestedEndDate;
    private String    reason;

    private MissionChangeRequestStatus status;
    private Long           resolvedBy;
    private String         resolvedByName;
    private OffsetDateTime resolvedAt;
    private String         resolutionNotes;
    private OffsetDateTime createdAt;
}
