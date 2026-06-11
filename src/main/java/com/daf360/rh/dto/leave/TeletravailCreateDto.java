package com.daf360.rh.dto.leave;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class TeletravailCreateDto {

    @NotNull(message = "La date de télétravail est obligatoire")
    private LocalDate date;

    private String reason;
}
