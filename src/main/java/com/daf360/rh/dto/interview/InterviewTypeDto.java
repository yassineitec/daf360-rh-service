package com.daf360.rh.dto.interview;

public record InterviewTypeDto(
        Long id,
        Long paysId,
        String name,
        String nameEn,
        String description,
        Integer orderIndex,
        Boolean isActive
) {}
