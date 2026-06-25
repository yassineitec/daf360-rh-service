package com.daf360.rh.dto.onboarding;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OnboardingKpiDto {
    private long pendingCount;
    private long profilesCreatedToday;
    private long incompleteProfiles;
    private Double avgCreationMinutes;
}
