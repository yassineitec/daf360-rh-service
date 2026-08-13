package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * One member of an interview panel. A {@link CandidateInterview} can carry several
 * interviewers; {@code CandidateInterview.interviewerUserId} keeps the first one as
 * the "lead" so pre-panel queries and reports keep working.
 */
@Entity
@Table(name = "candidate_interview_interviewers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateInterviewInterviewer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
