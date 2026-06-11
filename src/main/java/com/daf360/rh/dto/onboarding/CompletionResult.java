package com.daf360.rh.dto.onboarding;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class CompletionResult {
    private Long employeeProfileId;
    private Long candidateId;
    private Long userId;
    private Long workflowInstanceId;
    private String ms365Email;
    private String message;
}
