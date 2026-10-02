package com.daf360.rh.dto.leave;

import java.time.LocalDate;

/**
 * A range the employee already holds, so the date picker can refuse to overlap it.
 *
 * Pending and approved only — a refused request holds no days and must not block asking
 * again for the same dates, which was the point of refusing it.
 */
public record LeaveBlockingRangeDto(
        Long id,
        LocalDate dateDebut,
        LocalDate dateFin,
        String etat
) {}
