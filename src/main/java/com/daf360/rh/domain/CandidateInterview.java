package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.InterviewStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "candidate_interviews")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateInterview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "interview_type_id", nullable = false)
    private Long interviewTypeId;

    @Column(name = "scheduled_at", nullable = false, columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime scheduledAt;

    @Column(name = "location", length = 255, columnDefinition = "nvarchar(255)")
    private String location;

    @Column(name = "interviewer_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String interviewerNotes;

    @Column(name = "interviewer_user_id")
    private Long interviewerUserId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InterviewStatus status = InterviewStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 10)
    private InterviewResult result;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "graph_event_id", length = 255)
    private String graphEventId;

    @Column(name = "graph_organizer_email", length = 255)
    private String graphOrganizerEmail;

    @Column(name = "graph_join_url", length = 1000)
    private String graphJoinUrl;

    // ── RH validation of the PVs (V132) ─────────────────────────────────────────
    // `result` above is written ONLY by that validation now: the interviewers say what
    // they saw (CandidateInterviewReport), RH turns it into the decision.

    @Column(name = "validated_by")
    private Long validatedBy;

    @Column(name = "validated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime validatedAt;

    @Column(name = "validation_comment", length = 1000, columnDefinition = "nvarchar(1000)")
    private String validationComment;

    /** Stamped when the « dans une heure » reminder went out; reset when the slot moves. */
    @Column(name = "reminder_sent_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime reminderSentAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
