package com.daf360.rh.dto.document;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class DocumentUploadResponseDto {
    private Long   id;
    private Long   employeeProfileId;
    private String documentType;
    private String fileName;
    private String fileUrl;
    private Integer fileSizeKb;
    private String verificationStatus;
    private OffsetDateTime uploadedAt;
}
