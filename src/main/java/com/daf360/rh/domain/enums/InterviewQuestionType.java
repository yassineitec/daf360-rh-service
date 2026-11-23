package com.daf360.rh.domain.enums;

/**
 * How an interview question is answered in the PV (V135).
 * <ul>
 *   <li>{@code TEXT} — free text;</li>
 *   <li>{@code RATING} — a slider between the question's min and max, step apart;</li>
 *   <li>{@code SELECT} — exactly one of the question's options;</li>
 *   <li>{@code MULTI_SELECT} — one or more of the question's options.</li>
 * </ul>
 */
public enum InterviewQuestionType {
    TEXT, RATING, SELECT, MULTI_SELECT
}
