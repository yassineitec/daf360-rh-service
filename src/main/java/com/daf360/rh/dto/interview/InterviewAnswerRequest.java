package com.daf360.rh.dto.interview;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * One answer inside a PV (V135). Only the field matching the question's type is read:
 * {@code text} for TEXT, {@code number} for RATING, {@code options} for SELECT (one item)
 * and MULTI_SELECT (one or more).
 */
public record InterviewAnswerRequest(
        @NotNull Long questionId,
        String text,
        BigDecimal number,
        List<String> options
) {}
