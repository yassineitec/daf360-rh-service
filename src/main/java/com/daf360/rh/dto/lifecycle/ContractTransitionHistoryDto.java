package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ContractTransitionHistoryDto {

    private Long id;
    private Long contractId;
    private Long employeeProfileId;
    private String statutAvant;
    private String statutApres;
    private String actionCode;
    private Long triggeredByUserId;
    private OffsetDateTime triggeredAt;
    private String commentaire;
    private String documentReference;
}
