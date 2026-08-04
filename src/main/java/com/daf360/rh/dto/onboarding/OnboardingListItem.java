package com.daf360.rh.dto.onboarding;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data @Builder
public class OnboardingListItem {
    private Long candidateId;
    private String candidateFullName;
    private String appliedPosition;
    private Long paysId;
    private LocalDate expectedStartDate;
    private CandidateStatus candidateStatus;
    private String ms365Email;
    private ItProvisioningStatus itProvisioningStatus;
    private OffsetDateTime ms365EmailCreatedAt;
    private Long itProvisioningId;
    /**
     * Canonical GENDER value_code (MALE/FEMALE/OTHER/…), passed through so the list can
     * show a gendered avatar instead of initials. Nullable — the UI falls back to initials.
     */
    private String gender;
}
