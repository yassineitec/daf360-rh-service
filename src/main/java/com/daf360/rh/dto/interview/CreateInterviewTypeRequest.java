package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateInterviewTypeRequest(
        @NotNull Long paysId,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 500) String description,
        Integer orderIndex
) {}
