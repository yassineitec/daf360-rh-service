package com.daf360.rh.dto.ref;

/**
 * One selectable timezone for the admin dropdown.
 *
 * @param id            the IANA identifier — the ONLY part that gets persisted
 * @param label         "Africa/Tunis (UTC+01:00)", computed for the current instant
 * @param offsetSeconds current offset, for sorting only (changes with DST)
 */
public record TimezoneOptionDto(
        String id,
        String label,
        int    offsetSeconds
) {}
