package com.daf360.rh.dto.requests;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class GeneratedDocumentResponseDto {
    private Long           id;
    private Long           employeeRequestId;
    private String         documentType;
    private String         fileUrl;
    private String         verificationCode;
    private OffsetDateTime generatedAt;
    private Long           generatedBy;
}
