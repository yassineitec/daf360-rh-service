package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.AbsenceType;
import com.daf360.rh.domain.enums.LeaveStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveResponseDto {
    private Long id;
    private Long employeeId;
    private AbsenceType absenceType;
    private LocalDate startDate;
    private LocalDate endDate;
    private LeaveStatus status;
    private Integer workingDays;
    private String comment;
    private String rejectionReason;
    private Long managerValidatorId;
    private Long hrValidatorId;
    private LocalDateTime createdAt;
}
