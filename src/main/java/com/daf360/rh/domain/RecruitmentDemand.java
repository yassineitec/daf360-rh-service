package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[recruitment_demands] (V31 migration).
 * All User/Pays FKs are raw Long fields — no JPA entity exists for those tables in rh-service.
 */
@Entity
@Table(name = "recruitment_demands")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecruitmentDemand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    @Column(name = "job_title", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String jobTitle;

    @Column(name = "department", length = 255, columnDefinition = "nvarchar(255)")
    private String department;

    @Column(name = "required_profile", nullable = false, length = 2000, columnDefinition = "nvarchar(2000)")
    private String requiredProfile;

    @Column(name = "scope_of_work", nullable = false, length = 2000, columnDefinition = "nvarchar(2000)")
    private String scopeOfWork;

    @Column(name = "urgency_level_id", nullable = false)
    private Long urgencyLevelId;

    @Column(name = "recruitment_reason", length = 50)
    private String recruitmentReason;

    @Column(name = "need_description", length = 4000, columnDefinition = "nvarchar(4000)")
    private String needDescription;

    @Column(name = "job_exact_title", length = 255, columnDefinition = "nvarchar(255)")
    private String jobExactTitle;

    @Column(name = "csp_category_id")
    private Long cspCategoryId;

    @Column(name = "experience_level_id")
    private Long experienceLevelId;

    @Column(name = "education_level_id")
    private Long educationLevelId;

    @Column(name = "technical_skills", length = 2000, columnDefinition = "nvarchar(2000)")
    private String technicalSkillsJson;

    @Column(name = "soft_skills", length = 1000, columnDefinition = "nvarchar(1000)")
    private String softSkillsJson;

    @Column(name = "target_start_date")
    private LocalDate targetStartDate;

    @Column(name = "headcount", nullable = false)
    @Builder.Default
    private int headcount = 1;

    @Column(name = "budget_range", length = 100, columnDefinition = "nvarchar(100)")
    private String budgetRange;

    @Column(name = "additional_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String additionalNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 30)
    @Builder.Default
    private RecruitmentDemandStatus statut = RecruitmentDemandStatus.EN_ATTENTE;

    @Column(name = "submitted_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime submittedAt;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_comment", length = 500, columnDefinition = "nvarchar(500)")
    private String reviewComment;

    @Column(name = "candidate_count", nullable = false)
    @Builder.Default
    private int candidateCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
