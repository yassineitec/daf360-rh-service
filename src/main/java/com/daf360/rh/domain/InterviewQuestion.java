package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.InterviewQuestionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One question of an interview type's questionnaire (V135): what every interviewer of that
 * type answers in their PV, so the panel's opinions come back in the same shape.
 *
 * <p>Archived ({@code isActive = false}) rather than deleted once someone has answered it —
 * the answers point at it and keep a copy of its wording.
 */
@Entity
@Table(name = "interview_type_questions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_type_id", nullable = false)
    private Long interviewTypeId;

    @Column(name = "label", nullable = false, length = 500, columnDefinition = "nvarchar(500)")
    private String label;

    /** Libellé anglais facultatif ; l'écran retombe sur {@code label} quand il est vide. */
    @Column(name = "label_en", length = 500, columnDefinition = "nvarchar(500)")
    private String labelEn;

    @Column(name = "help_text", length = 1000, columnDefinition = "nvarchar(1000)")
    private String helpText;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_type", nullable = false, length = 20)
    private InterviewQuestionType responseType;

    /** JSON array of strings — SELECT / MULTI_SELECT only. */
    @Column(name = "options_json", columnDefinition = "nvarchar(max)")
    private String optionsJson;

    @Column(name = "rating_min")
    private Integer ratingMin;

    @Column(name = "rating_max")
    private Integer ratingMax;

    @Column(name = "rating_step")
    private Integer ratingStep;

    @Column(name = "rating_min_label", length = 100, columnDefinition = "nvarchar(100)")
    private String ratingMinLabel;

    @Column(name = "rating_max_label", length = 100, columnDefinition = "nvarchar(100)")
    private String ratingMaxLabel;

    @Builder.Default
    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    @Builder.Default
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
