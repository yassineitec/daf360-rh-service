package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class RegimeRoleAssignmentResponse {
    private Long id;
    private Long regimeId;
    private String regimeLabelFr;
    private Long roleId;
    private String roleName;
    private Long paysId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String notes;
    private Long assignedBy;
    private OffsetDateTime assignedAt;
}
