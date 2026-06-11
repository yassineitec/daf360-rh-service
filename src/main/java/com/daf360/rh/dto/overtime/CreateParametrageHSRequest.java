package com.daf360.rh.dto.overtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalTime;

@Data
public class CreateParametrageHSRequest {

    @NotNull
    private Long paysId;

    @NotBlank
    private String typeCalculHs;  // WEEKEND_ONLY / AFTER_WORK_HOURS / MIXTE

    private LocalTime heureDebutTravail;
    private LocalTime heureFinTravail;
    private String jourDebutSemaine;  // ex: MONDAY
    private String jourFinSemaine;    // ex: FRIDAY or THURSDAY
}
