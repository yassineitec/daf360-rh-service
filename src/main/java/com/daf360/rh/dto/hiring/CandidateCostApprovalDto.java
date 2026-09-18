package com.daf360.rh.dto.hiring;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class CandidateCostApprovalDto {

    private Long            id;
    private Long            candidateId;
    /** The offer round this decision gates, or null for a standalone budget pre-validation. */
    private Long            jobOfferId;
    /** The figure offered on that round — null on a pre-validation, which offers nothing yet. */
    private BigDecimal      proposedSalary;
    private String          candidateFirstName;
    private String          candidateLastName;
    private String          appliedPosition;
    private String          candidateLocation;
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
    private BigDecimal      contrePropSalaire;
}
