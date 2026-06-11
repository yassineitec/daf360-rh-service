package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class LeaveResponseDto {
    private Long id;
    private Long employeeId;
    private LeaveType leaveType;
    private LeaveCategory category;
    private LocalDate startDate;   // converted from OffsetDateTime in service layer
    private LocalDate endDate;
    private DemandeEtat etatDemande;
    private BigDecimal workingDays;
    private BigDecimal totalJours;
    private String comment;
    private String rejectionReason;
    private Long managerValidatorId;
    private Long hrValidatorId;
    private OffsetDateTime createdAt;
}
