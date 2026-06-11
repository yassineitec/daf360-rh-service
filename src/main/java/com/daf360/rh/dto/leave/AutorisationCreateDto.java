package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AutorisationCreateDto {

    @NotNull(message = "La date est obligatoire")
    private LocalDate date;

    @NotNull(message = "Le nombre d'heures est obligatoire")
    @Min(value = 1, message = "Minimum 1 heure")
    @Max(value = 8, message = "Maximum 8 heures par jour")
    private Long nombreHeures;

    private String reason;
}
