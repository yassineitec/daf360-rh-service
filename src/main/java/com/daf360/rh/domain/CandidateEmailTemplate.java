package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [dbo].[candidate_email_templates] (V134) — the text of an email sent to a candidate,
 * per entity. Subject and body are plain text with {@code {{tokens}}}; RH sees the rendered
 * email before it goes and may edit that one send.
 *
 * <p>Codes: {@code OFFER} (first offer), {@code OFFER_REVISED} (any later round). No row for
 * a code and entity ⇒ the built-in default of {@code CandidateEmailTemplateService}.
 */
@Entity
@Table(name = "candidate_email_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateEmailTemplate {

    public static final String CODE_OFFER         = "OFFER";
    public static final String CODE_OFFER_REVISED = "OFFER_REVISED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "subject", nullable = false, length = 500, columnDefinition = "nvarchar(500)")
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "nvarchar(max)")
    private String body;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
