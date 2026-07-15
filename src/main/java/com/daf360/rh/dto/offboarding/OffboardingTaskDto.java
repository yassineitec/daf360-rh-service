package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class OffboardingTaskDto {

    private Long id;
    private Long workflowInstanceId;
    private String taskCode;
    private String taskLabel;
    private String ownerRole;
    private Long ownerUserId;
    private Boolean isMandatory;
    private Boolean isBlocking;
    private LocalDate dueDate;
    private String status;
    private Long completedBy;
    private OffsetDateTime completedAt;
    private Long skippedBy;
    private String skipReason;
    private String comments;
    private String attachedDocumentUrl;
    private OffsetDateTime slaBreachDate;
    private OffsetDateTime createdAt;
}
