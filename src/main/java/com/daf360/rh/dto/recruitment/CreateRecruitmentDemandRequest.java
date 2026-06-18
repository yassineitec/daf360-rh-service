package com.daf360.rh.dto.recruitment;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CreateRecruitmentDemandRequest {

    @NotNull
    private Long paysId;

    @NotBlank @Size(max = 255)
    private String jobTitle;

    @Size(max = 255)
    private String department;

    @NotBlank @Size(max = 2000)
    private String requiredProfile;

    @NotBlank @Size(max = 2000)
    private String scopeOfWork;

    @NotNull
    private Long urgencyLevelId;

    /** CREATION_POSTE | REMPLACEMENT | ACCROISSEMENT */
    @Pattern(regexp = "CREATION_POSTE|REMPLACEMENT|ACCROISSEMENT",
             message = "recruitmentReason doit être CREATION_POSTE, REMPLACEMENT ou ACCROISSEMENT")
    private String recruitmentReason;

    @Size(max = 4000)
    private String needDescription;

    @Size(max = 255)
    private String jobExactTitle;

    private Long cspCategoryId;

    private Long experienceLevelId;

    private Long educationLevelId;

    @Size(max = 20)
    private List<String> technicalSkills;

    @Size(max = 10)
    private List<String> softSkills;

    private LocalDate targetStartDate;

    @Min(1) @Max(50)
    private int headcount = 1;

    @Size(max = 100)
    private String budgetRange;

    @Size(max = 1000)
    private String additionalNotes;
}
