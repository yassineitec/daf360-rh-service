package com.daf360.rh.dto.offboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class OffboardingAssetReturnDto {

    private Long id;
    private Long workflowInstanceId;
    private Long taskId;
    private String assetDescription;
    private String assetType;
    private LocalDate expectedReturnDate;
    private LocalDate actualReturnDate;
    private String conditionOnReturn;
    private Long confirmedBy;
    private OffsetDateTime confirmedAt;
    private Boolean isWrittenOff;
    private Long writeOffApprovedBy;
    private String writeOffReason;
    private OffsetDateTime createdAt;
}
