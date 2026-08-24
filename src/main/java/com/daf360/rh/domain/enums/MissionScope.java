package com.daf360.rh.domain.enums;

/**
 * Whether a mission stays inside the employee's own country or crosses a border.
 * Matches CK_Mission_Scope in DAF360_HR.missions (V78).
 *
 * It is not cosmetic: an INTERNATIONAL mission is what makes a destination country,
 * a visa fee and a passport pickup date meaningful.
 */
public enum MissionScope {
    NATIONAL,
    INTERNATIONAL
}
