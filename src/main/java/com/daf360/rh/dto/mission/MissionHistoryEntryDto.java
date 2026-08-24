package com.daf360.rh.dto.mission;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class MissionHistoryEntryDto {
    private Long           id;
    private String         fromStatus;
    private String         toStatus;
    private Long           actorUserId;
    private String         actorName;
    private String         notes;
    private OffsetDateTime createdAt;
}
