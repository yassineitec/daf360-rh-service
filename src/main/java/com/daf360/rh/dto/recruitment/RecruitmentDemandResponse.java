package com.daf360.rh.dto.recruitment;

import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class RecruitmentDemandResponse {

    private Long id;
    private Long createdByUserId;
    private Long paysId;

    private String jobTitle;
    private String jobExactTitle;
    /** Department LABEL — kept for older readers; `departmentId` is the real link. */
    private String department;

    /**
     * Position dimensions (V72), the same tables employee_profiles uses. Returned so the
     * candidate form can PREFILL its own position fields from the chosen vacancy instead of
     * matching labels — that is the whole reason these are on the demand.
     */
    private Long   departmentId;
    private Long   gradeId;
    private String gradeLabel;
    private Long   disciplineId;
    private String disciplineLabel;
    private String requiredProfile;
    private String scopeOfWork;
    private String needDescription;

    private String recruitmentReason;
    private String recruitmentReasonLabel;

    private Long urgencyLevelId;
    private String urgencyLevelLabel;

    private Long cspCategoryId;
    private String cspCategoryLabel;

    private Long experienceLevelId;
    private String experienceLevelLabel;
    /**
     * The level's `value_code` (DEBUTANT / JUNIOR / …), not just its id and label.
     *
     * The candidate form maps the required level onto its own experience-in-YEARS slider, and
     * doing that off the label would break the moment someone rewords it.
     */
    private String experienceLevelCode;

    private Long educationLevelId;
    private String educationLevelLabel;

    private List<String> technicalSkills;
    private List<String> softSkills;

    private LocalDate targetStartDate;
    private int headcount;
    private String budgetRange;
    private String additionalNotes;

    private RecruitmentDemandStatus statut;
    private OffsetDateTime submittedAt;
    private Long reviewedByUserId;
    private OffsetDateTime reviewedAt;
    private String reviewComment;

    private int candidateCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
