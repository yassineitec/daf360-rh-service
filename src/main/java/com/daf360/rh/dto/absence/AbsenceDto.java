package com.daf360.rh.dto.absence;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AbsenceDto {

    private Long      id;
    private String    leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String    etatDemande;
    private Double    totalJours;
    private String    comment;
}
