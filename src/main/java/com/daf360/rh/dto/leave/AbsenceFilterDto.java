package com.daf360.rh.dto.leave;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AbsenceFilterDto {
    private Long      profileId;
    private String    etatDemande;
    private LocalDate dateFrom;
    private LocalDate dateTo;
}
