package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.DocumentType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentResponseDto {
    private Long id;
    private Long employeeId;
    private DocumentType documentType;
    private String fileName;
    private Integer version;
    private Boolean confidential;
    private LocalDateTime createdAt;
    private String createdBy;
}
