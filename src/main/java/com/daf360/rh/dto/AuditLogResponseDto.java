package com.daf360.rh.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AuditLogResponseDto {
    private Long id;
    private String userId;
    private String action;
    private String entityType;
    private String entityId;
    private String oldValue;
    private String newValue;
    private String ipAddress;
    private String status;
    private String module;
    private OffsetDateTime timestamp;
}
