package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AbsenceCreateDto {

    @NotNull(message = "Le type de congé est obligatoire")
    private LeaveType leaveType;

    /** Defaults to FULL_DAY if null. */
    private LeaveCategory category;

    @NotNull(message = "La date de début est obligatoire")
    private LocalDate startDate;

    @NotNull(message = "La date de fin est obligatoire")
    private LocalDate endDate;

    private String comment;

    private Boolean justificatif;
}
