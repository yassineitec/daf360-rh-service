package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;

public record ProbationAlertDto(
        Long      profileId,
        String    fullName,
        String    photoUrl,
        LocalDate finPeriodeEssai,
        long      joursRestants,
        LocalDate contractEndDate,
        String    department,
        String    roleName,
        String    gender
) {}
