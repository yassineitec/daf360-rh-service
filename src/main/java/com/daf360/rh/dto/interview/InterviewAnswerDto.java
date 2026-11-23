package com.daf360.rh.dto.interview;

import java.math.BigDecimal;
import java.util.List;

/**
 * One answer of a PV, as the interviewer was asked it (V135): label, type and scale come
 * from the copy taken at answer time, not from the question as it reads today.
 */
public record InterviewAnswerDto(
        Long questionId,
        String label,
        String labelEn,
        String responseType,
        Integer ratingMin,
        Integer ratingMax,
        Integer order,
        String text,
        BigDecimal number,
        List<String> options
) {}
