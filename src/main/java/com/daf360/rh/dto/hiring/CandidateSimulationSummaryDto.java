package com.daf360.rh.dto.hiring;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
    /** Net évalué par la RH, tel qu'il figure sur la fiche candidat (`candidates.salaire_net_rh`). */
    private BigDecimal      salaireNetRh;
    /** Net demandé par le candidat (`candidates.salaire_net_candidat`). */
    private BigDecimal      salaireNetCandidat;
}
