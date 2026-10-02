package com.daf360.rh.dto.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One of the caller's own leave periods, for the home calendar.
 *
 * Mirrors MissionCalendarEventDto: a compact, self-contained shape the shell merges into its
 * event feed. It carries the RANGE rather than one date — the calendar expands it into the
 * days it covers, so a congé reads as a run of cells rather than a single marker on the
 * first day.
 *
 * {@code typeCode} travels alongside {@code typeLabel} because the label is HR-editable and
 * the code is not: anything the UI keys off (an icon, a colour) must key off the code.
 *
 * Pending is included, and {@code etat} is how the calendar tells the two apart — an employee
 * planning around a request wants to see it before it is signed off, but should not mistake
 * it for approved.
 */
public record LeaveCalendarEventDto(
        Long id,
        String typeCode,
        String typeLabel,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal totalJours,
        String etat,
        String category
) {}
