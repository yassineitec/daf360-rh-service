package com.daf360.rh.dto.regime;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One personal regime assignment of an employee (employee_regime_assignments, V127). */
@Data
@Builder
public class EmployeeRegimeAssignmentDto {
    private Long id;
    private Long regimeId;
    private String regimeLabelFr;
    private String regimeLabelEn;
    private LocalDate effectiveFrom;
    /** null = open-ended */
    private LocalDate effectiveTo;
    private String reason;
    /** MANUAL | ONBOARDING | MIGRATION */
    private String source;
    /** Covers the employee's today. */
    private boolean inForce;
    /** Starts after the employee's today. */
    private boolean upcoming;
    private OffsetDateTime assignedAt;
}
