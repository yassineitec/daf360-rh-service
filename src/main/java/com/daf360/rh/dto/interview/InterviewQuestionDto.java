package com.daf360.rh.dto.interview;

import java.util.List;

/**
 * One questionnaire question (V135). {@code options} is filled for SELECT / MULTI_SELECT,
 * the {@code rating*} fields for RATING. {@code answerCount} = how many PVs answered it —
 * once above 0 the question can only be archived, and its type / scale are frozen.
 */
public record InterviewQuestionDto(
        Long id,
        Long interviewTypeId,
        String label,
        String labelEn,
        String helpText,
        String responseType,
        List<String> options,
        Integer ratingMin,
        Integer ratingMax,
        Integer ratingStep,
        String ratingMinLabel,
        String ratingMaxLabel,
        boolean required,
        Integer orderIndex,
        boolean active,
        long answerCount
) {}
