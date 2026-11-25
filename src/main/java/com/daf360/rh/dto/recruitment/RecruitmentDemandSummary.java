package com.daf360.rh.dto.recruitment;

import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RecruitmentDemandSummary {

    private Long id;
    private String jobTitle;
    private String jobExactTitle;
    private String department;
    /** English label of the department (admin → données de référence), display-only. */
    private String departmentLabelEn;
    private RecruitmentDemandStatus statut;
    private String urgencyLevelLabel;
    private String recruitmentReason;
    private String recruitmentReasonLabel;
    private int headcount;
    private int candidateCount;
    /** Posts filled (V137): offer agreed — onboarding, IT or hired. */
    private int positionsFilled;
    private int hiredCount;
    /** AUTO_FILLED | MANUAL | null (V137). */
    private String closedReason;
    private OffsetDateTime submittedAt;
    private Long createdByUserId;
}
