package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class OffboardingWorkflowInstanceDto {

    private Long id;
    private Long paysId;
    private Long employeeProfileId;
    private Long contractId;
    private LocalDate triggerDate;
    private LocalDate lastWorkingDay;
    private String departureReason;
    private String departureNotes;
    private String status;
    private Long initiatedBy;
    private Long validatedBy;
    private OffsetDateTime validatedAt;
    private Long cancelledBy;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;
    private Boolean slaBreachFlag;
    private OffsetDateTime completionDate;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<OffboardingTaskDto> tasks;

    private String employeeFullName;
    private Long   handoverManagerProfileId;
    private String handoverManagerName;
}
