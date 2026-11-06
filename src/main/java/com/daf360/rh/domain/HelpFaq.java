package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A question / answer typed by an HR administrator (Admin › Assistant & FAQ), shown by the
 * help assistant and the « Aide & guides » page next to the FAQ shipped with the app.
 * Company-wide, not per entity. French is required; English and Arabic fall back to it.
 * Table created by sql/2026-11-05_help_faq.sql.
 */
@Entity
@Table(name = "help_faq")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HelpFaq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Guide key of the page the question is about (front `GUIDE_PAGES.guideKey`, e.g. "home"). */
    @Column(name = "page_key", nullable = false, length = 80, columnDefinition = "nvarchar(80)")
    private String pageKey;

    @Column(name = "question_fr", nullable = false, length = 300, columnDefinition = "nvarchar(300)")
    private String questionFr;

    @Column(name = "answer_fr", nullable = false, length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerFr;

    @Column(name = "question_en", length = 300, columnDefinition = "nvarchar(300)")
    private String questionEn;

    @Column(name = "answer_en", length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerEn;

    @Column(name = "question_ar", length = 300, columnDefinition = "nvarchar(300)")
    private String questionAr;

    @Column(name = "answer_ar", length = 2000, columnDefinition = "nvarchar(2000)")
    private String answerAr;

    @Builder.Default
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex = 0;

    /** Published: shown by the assistant and the help page. Inactive = kept but hidden. */
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
