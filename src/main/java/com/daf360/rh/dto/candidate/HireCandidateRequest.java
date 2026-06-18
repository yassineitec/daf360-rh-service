package com.daf360.rh.dto.candidate;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class HireCandidateRequest {

    @NotNull
    private LocalDate hireDate;

    /** Optional override — if null the code derives the type from the candidate's employment_type_id. */
    private String contractTypeCode;

    /** Required when contractTypeCode is CDD, CIVP, STAGE, or DETACHEMENT. */
    private LocalDate dateFinPrevue;

    /** For CDI: extends trial period from 3 to 6 months. */
    private boolean managerProfile;

    /** Free-text comment recorded in the lifecycle transition history. */
    private String notes;
}
