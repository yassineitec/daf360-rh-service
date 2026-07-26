package com.daf360.rh.dto.hiring;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class CandidateCostApprovalDto {

    private Long            id;
    private Long            candidateId;
    private String          candidateFirstName;
    private String          candidateLastName;
    private Long            paysId;
    private Integer         fiscalYear;
    private BigDecimal      salaireNetRh;
    private BigDecimal      salaireNetCandidat;
    private String          contractTypeCode;
    private String          simulationSnapshot;
    private String          status;
    private Long            submittedBy;
    private OffsetDateTime  submittedAt;
    private Long            approvedBy;
    private OffsetDateTime  approvedAt;
    private String          approvalNotes;
}
