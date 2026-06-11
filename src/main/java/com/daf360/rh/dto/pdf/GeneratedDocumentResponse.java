package com.daf360.rh.dto.pdf;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class GeneratedDocumentResponse {
    private Long           id;
    private Long           employeeRequestId;
    private String         documentType;
    private String         fileUrl;
    private String         verificationCode;
    private OffsetDateTime generatedAt;
    private Long           generatedBy;
    private String         downloadUrl;
}
