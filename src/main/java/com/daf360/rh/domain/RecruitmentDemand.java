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

    /**
     * The department LABEL. Kept alongside `department_id` (V72) for the demands that predate
     * it — new demands write both, so old readers keep working.
     */
    @Column(name = "department", length = 255, columnDefinition = "nvarchar(255)")
    private String department;

    /**
     * The position's dimensions (V72) — the SAME tables employee_profiles points at
     * (`grades`, `disciplines`, `departments`), so a vacancy and the profile it becomes cannot
     * describe the position differently.
     *
     * Plain Long FKs rather than @ManyToOne associations, matching how this entity already
     * treats every other FK (pays, user, list values): it is read as a flat row by the pipeline
     * SQL as often as through JPA, and lazy proxies here bought nothing.
     *
     * gradeId also makes the préavis default (V64 lives on the grade) knowable when the
     * vacancy is opened rather than when the contract is signed.
     */
    @Column(name = "grade_id")
    private Long gradeId;

    @Column(name = "discipline_id")
    private Long disciplineId;

    @Column(name = "department_id")
    private Long departmentId;

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

    /** Every candidature linked to the demand, whatever its status — kept by DemandFillService. */
    @Column(name = "candidate_count", nullable = false)
    @Builder.Default
    private int candidateCount = 0;

    /**
     * Posts taken (V137): candidates whose offer is agreed — onboarding / IT / hired. Counted
     * against {@link #headcount}; maintained by DemandFillService, never set by hand.
     */
    @Column(name = "positions_filled", nullable = false)
    @Builder.Default
    private int positionsFilled = 0;

    /** Of {@link #positionsFilled}, the recruits already HIRED (V137). */
    @Column(name = "hired_count", nullable = false)
    @Builder.Default
    private int hiredCount = 0;

    @Column(name = "closed_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    /**
     * AUTO_FILLED — closed by DemandFillService because every post is filled; reopens by
     * itself when a recruit drops out. MANUAL — closed by someone; never reopened automatically.
     */
    @Column(name = "closed_reason", length = 20)
    private String closedReason;

    public static final String CLOSED_AUTO_FILLED = "AUTO_FILLED";
    public static final String CLOSED_MANUAL      = "MANUAL";

    /** Posts still to fill — never negative (a race can overfill by one). */
    public int remainingPositions() {
        return Math.max(0, headcount - positionsFilled);
    }

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
