package com.daf360.rh.dto.dashboard;

import java.time.LocalDate;

public record RecentActivityDto(
        Long profileId,
        String fullName,
        String action,
        LocalDate date,
        String type
) {}
