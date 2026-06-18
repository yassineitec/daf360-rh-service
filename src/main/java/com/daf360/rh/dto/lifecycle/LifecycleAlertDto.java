package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class LifecycleAlertDto {

    private Long id;
    private Long contractId;
    private Long employeeProfileId;
    private String alertType;
    private LocalDate alertDate;
    private LocalDate targetDate;
    private String recipients;
    private Boolean isSent;
    private OffsetDateTime sentAt;
    private Boolean isAcknowledged;
    private Long acknowledgedBy;
    private OffsetDateTime acknowledgedAt;
}
