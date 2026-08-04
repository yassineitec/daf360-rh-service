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
    /** SEASONAL | EMPLOYEE_OVERRIDE | ROLE_ASSIGNMENT | DEFAULT — why this regime was chosen. */
    private String    assignmentLevel;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long      paysId;
    /** True when this regime won because of its seasonal window. */
    private Boolean   isSeasonal;

    // --- pointage-friendly aliases (computed by RegimeResolutionService) ---
    private String       regimeName;        // = regimeLabelFr
    private Double       heuresJour;        // = hoursPerWeek / daysPerWeek, null if not derivable
    private String       heureDebut;        // = startTime formatted "HH:mm", null if unset
    private String       heureFin;          // = endTime   formatted "HH:mm", null if unset
    private Integer      pauseDejeuner;     // = breakDurationMin
    private List<String> joursOuvrables;    // non-weekend days from pays_weekends

    // --- break windows for this regime (from active break_templates), "HH:mm" ---
    private List<BreakWindow> breaks;

    @Data
    public static class BreakWindow {
        private String  start;
        private String  end;
        /** Pointage status to switch into for this window (may be null). */
        private String  statusCode;
        private String  labelFr;
        private String  labelEn;
        private Integer durationMin;
        /** ALL | WEEKDAYS | WEEKEND | comma-separated day codes. */
        private String  appliesToDays;

        public BreakWindow() {}

        public BreakWindow(String start, String end) { this.start = start; this.end = end; }

        public BreakWindow(String start, String end, String statusCode, String labelFr,
                           String labelEn, Integer durationMin, String appliesToDays) {
            this.start         = start;
            this.end           = end;
            this.statusCode    = statusCode;
            this.labelFr       = labelFr;
            this.labelEn       = labelEn;
            this.durationMin   = durationMin;
            this.appliesToDays = appliesToDays;
        }
    }
}
