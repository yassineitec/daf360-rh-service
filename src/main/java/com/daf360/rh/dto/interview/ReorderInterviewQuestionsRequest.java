package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** The type's question ids in their new order — every question of the type, archived ones included. */
public record ReorderInterviewQuestionsRequest(
        @NotEmpty List<Long> questionIds
) {}
