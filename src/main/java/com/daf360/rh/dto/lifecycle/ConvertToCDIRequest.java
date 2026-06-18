package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ConvertToCDIRequest {

    @NotNull
    private LocalDate cdiStartDate;

    private String commentaire;
}
