package com.daf360.rh.dto.regime;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
public class ResolvedRegimeDto {

    // --- core regime identity ---
    private Long    regimeId;
    private String  regimeCode;
    private String  regimeLabelFr;
    private String  regimeLabelEn;

    // --- raw working-time fields ---
    private BigDecimal hoursPerWeek;
    private Integer    daysPerWeek;
    private LocalTime  startTime;
    private LocalTime  endTime;
    private Boolean    isFlexible;
    private Integer    breakDurationMin;
    private Boolean    overtimeAllowed;
    private BigDecimal maxHoursPerDay;

    // --- assignment metadata ---
    private String    assignmentLevel;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    // --- pointage-friendly aliases (computed by RegimeResolutionService) ---
    private String       regimeName;        // = regimeLabelFr
    private Double       heuresJour;        // = hoursPerWeek / daysPerWeek
    private String       heureDebut;        // = startTime formatted "HH:mm"
    private String       heureFin;          // = endTime   formatted "HH:mm"
    private Integer      pauseDejeuner;     // = breakDurationMin
    private List<String> joursOuvrables;    // derived from daysPerWeek
}
