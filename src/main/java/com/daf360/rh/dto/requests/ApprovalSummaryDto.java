package com.daf360.rh.dto.requests;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ApprovalSummaryDto {
    private Long           id;
    private String         level;      // L1 | L2
    private Long           approverId;
    private String         decision;   // APPROVED | REJECTED
    private String         comment;
    private OffsetDateTime decisionDate;
}
