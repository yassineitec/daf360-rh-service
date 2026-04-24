package com.daf360.rh.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogResponseDto {
    private Long id;
    private String actorId;
    private String action;
    private String entity;
    private Long entityId;
    private String beforeValue;
    private String afterValue;
    private String ip;
    private LocalDateTime createdAt;
}
