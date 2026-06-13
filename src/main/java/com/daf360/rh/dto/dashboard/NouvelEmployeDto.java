package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;

public record NouvelEmployeDto(
        Long profileId,
        String fullName,
        String photoUrl,
        LocalDate hireDate,
        String department,
        String grade
) {}
