package com.daf360.rh.domain.enums;

/**
 * Who an absence type is available to.
 *
 * Carried over with the configurable leave-type model. Stored and editable in the type
 * admin screen, but NOT enforced anywhere in the timesheet as of master (2026-08-18) —
 * maternity is offered to everyone. Reproduced here so the data round-trips; enforcing it
 * is a decision, not a port.
 */
public enum Gender {
    MALE,
    FEMALE,
    ALL
}
