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

    /** Department LABEL — kept for callers that only have a name. Prefer `departmentId`. */
    @Size(max = 255)
    private String department;

    /**
     * Position dimensions (V72), from the same tables the employee profiles use. Optional:
     * a manager raising a need does not always know the grade, and forcing one would invent
     * data. When `gradeId` IS given, the préavis default is known from the vacancy onwards.
     */
    private Long gradeId;
    private Long disciplineId;
    private Long departmentId;

    @NotBlank @Size(max = 2000)
    private String requiredProfile;

    @NotBlank @Size(max = 2000)
    private String scopeOfWork;

    /**
     * Either this OR {@link #urgencyLevelCode} — checked in the service, not here.
     *
     * No longer @NotNull: the self-service hiring form drives urgency as a slider over a
     * HARDCODED scale, so it holds codes, not ids. Requiring the id would force that form to
     * fetch the configurable list purely to translate a fixed scale into primary keys, and a
     * failed fetch would then block a required field.
     */
    private Long urgencyLevelId;

    /** `configurable_list_values.value_code` under URGENCY_LEVEL — e.g. URGENT. */
    @Size(max = 50)
    private String urgencyLevelCode;

    /** Same idea for the experience scale — `value_code` under EXPERIENCE_LEVEL. */
    @Size(max = 50)
    private String experienceLevelCode;

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
