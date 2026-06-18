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
    private RecruitmentDemandStatus statut;
    private String urgencyLevelLabel;
    private String recruitmentReason;
    private String recruitmentReasonLabel;
    private int headcount;
    private int candidateCount;
    private OffsetDateTime submittedAt;
    private Long createdByUserId;
}
