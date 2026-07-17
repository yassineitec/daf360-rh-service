package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class OffboardingTaskCatalogDto {
    private Long id;
    private Long paysId;
    private String contractType;
    private String taskCode;
    private String taskLabel;
    private String ownerRole;
    private Boolean isMandatory;
    private Boolean isBlocking;
    private Integer slaWorkingDays;
    private Integer orderIndex;
    private Boolean isActive;
    private OffsetDateTime createdAt;
}
