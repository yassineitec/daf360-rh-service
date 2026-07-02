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

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
