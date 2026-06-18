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
    private String department;
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
