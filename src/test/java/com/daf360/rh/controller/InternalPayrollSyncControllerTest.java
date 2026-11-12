package com.daf360.rh.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marital status → payroll's "married" flag (IRPP family deduction). */
class InternalPayrollSyncControllerTest {

    @Test
    void listCodeMarried_isMarried() {
        assertTrue(InternalPayrollSyncController.isMarried("MARRIED"));
        assertTrue(InternalPayrollSyncController.isMarried(" married "));
    }

    @Test
    void legacyFreeTextFrench_isMarried() {
        assertTrue(InternalPayrollSyncController.isMarried("Marié"));
        assertTrue(InternalPayrollSyncController.isMarried("Mariée"));
        assertTrue(InternalPayrollSyncController.isMarried("Marie(e)"));
    }

    @Test
    void otherStatusesAndNull_areNotMarried() {
        assertFalse(InternalPayrollSyncController.isMarried(null));
        assertFalse(InternalPayrollSyncController.isMarried("SINGLE"));
        assertFalse(InternalPayrollSyncController.isMarried("DIVORCED"));
        assertFalse(InternalPayrollSyncController.isMarried("WIDOWED"));
    }
}
