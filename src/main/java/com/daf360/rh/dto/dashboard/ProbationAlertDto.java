package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;

public record ProbationAlertDto(
        Long profileId,
        String fullName,
        String photoUrl,
        LocalDate probationEndDate,
        long joursRestants
) {}
