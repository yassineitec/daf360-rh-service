package com.daf360.rh.domain.enums;

/**
 * Matches the actual values stored in absences.type in DAF360_HR.
 * Replaces the old AbsenceType enum which used English names (PAID_LEAVE, SICK_LEAVE…)
 * that never existed in the database.
 */
public enum LeaveType {
    CONGE,          // congé annuel
    MALADIE,        // arrêt maladie
    MATERNITE,      // congé maternité
    PATERNITE,      // congé paternité
    EXCEPTIONNEL,   // congé exceptionnel
    DEUIL_AUTRE     // deuil / autre motif
}
