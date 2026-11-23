package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One interviewer's PV for one interview (V132): what happened, and their opinion on the
 * candidate. One row per panel member — a panel of three gives three PVs, which RH reads
 * side by side before validating.
 *
 * <p>Editable by its author until RH validates the interview; frozen afterwards, since the
 * decision was taken on what it said.
 */
@Entity
@Table(name = "candidate_interview_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateInterviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Column(name = "summary", nullable = false, length = 4000, columnDefinition = "nvarchar(4000)")
    private String summary;

    /** FAVORABLE / RESERVED / UNFAVORABLE — see {@code InterviewOpinion}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "opinion", nullable = false, length = 20)
    private com.daf360.rh.domain.enums.InterviewOpinion opinion;

    @Column(name = "submitted_at", nullable = false, columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime submittedAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
