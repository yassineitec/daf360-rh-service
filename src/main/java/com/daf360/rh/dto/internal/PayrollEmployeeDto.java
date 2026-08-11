package com.daf360.rh.dto.internal;

import java.math.BigDecimal;

/**
 * Lightweight projection of EmployeeProfile fields consumed by the payroll-service
 * for cohort aggregate simulations. No personal-data fields (IBAN, national-id, etc.).
 */
public record PayrollEmployeeDto(
        Long   userId,
        Long   paysId,
        String contractType,
        String grade,
        String discipline,
        BigDecimal salaireNetRh
) {}
