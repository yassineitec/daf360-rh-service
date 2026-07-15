package com.daf360.rh.dto.offboarding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class StartOffboardingRequestDto {

    @NotNull
    private Long employeeProfileId;

    /** Optional — if null the service resolves the active contract type */
    private Long contractId;

    @NotNull
    private LocalDate triggerDate;

    private LocalDate lastWorkingDay;

    @NotBlank
    private String departureReason;

    private String departureNotes;
}
