package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveRequestDto {

    @NotNull
    private Long employeeId;

    @NotNull
    private LeaveType leaveType;

    /** Full day / half-day morning / half-day afternoon. Defaults to FULL_DAY if null. */
    private LeaveCategory category;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private String comment;
}
