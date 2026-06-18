package com.daf360.rh.dto.candidate;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HireCandidateResponse {
    private Long   candidateId;
    private Long   employeeProfileId;
    private Long   contractId;
    private String contractTypeCode;
    private Long   userId;
    private String message;
}
