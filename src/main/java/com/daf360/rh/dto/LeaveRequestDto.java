package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.AbsenceType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class LeaveRequestDto {

    @NotNull
    private Long employeeId;

    @NotNull
    private AbsenceType absenceType;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private String comment;
}
