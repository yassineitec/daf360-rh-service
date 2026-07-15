package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class ExitInterviewDto {

    private Long id;
    private Long workflowInstanceId;
    private Long conductedBy;
    private LocalDate conductedDate;
    private String departureReasons;
    private String feedbackText;
    private Boolean isAnonymised;
    private OffsetDateTime anonymisedAt;
    private String visibleToRoles;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
