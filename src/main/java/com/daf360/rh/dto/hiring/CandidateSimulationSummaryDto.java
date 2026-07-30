package com.daf360.rh.dto.hiring;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CandidateSimulationSummaryDto {

    private Long            candidateId;
    private String          firstName;
    private String          lastName;
    private String          appliedPosition;
    private String          candidateLocation;
    private Long            paysId;
    private long            simulationCount;
    private OffsetDateTime  latestSubmittedAt;
    private String          latestStatus;
}
