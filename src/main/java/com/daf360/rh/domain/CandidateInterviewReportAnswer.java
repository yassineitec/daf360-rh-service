package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.InterviewQuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One interviewer's answer to one questionnaire question, inside their PV (V135).
 *
 * <p>The question's wording, type and bounds are copied here at answer time: a PV must keep
 * reading what the interviewer was actually asked, even after RH rewords the question,
 * changes its scale or archives it.
 */
@Entity
@Table(name = "candidate_interview_report_answers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateInterviewReportAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "question_id")
    private Long questionId;

    @Column(name = "question_label", nullable = false, length = 500, columnDefinition = "nvarchar(500)")
    private String questionLabel;

    @Column(name = "question_label_en", length = 500, columnDefinition = "nvarchar(500)")
    private String questionLabelEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_type", nullable = false, length = 20)
    private InterviewQuestionType responseType;

    @Column(name = "rating_min")
    private Integer ratingMin;

    @Column(name = "rating_max")
    private Integer ratingMax;

    @Builder.Default
    @Column(name = "question_order", nullable = false)
    private Integer questionOrder = 0;

    @Column(name = "value_text", length = 4000, columnDefinition = "nvarchar(4000)")
    private String valueText;

    @Column(name = "value_number", precision = 9, scale = 2)
    private BigDecimal valueNumber;

    /** JSON array of the chosen option(s) — SELECT holds one, MULTI_SELECT one or more. */
    @Column(name = "value_options_json", columnDefinition = "nvarchar(max)")
    private String valueOptionsJson;
}
