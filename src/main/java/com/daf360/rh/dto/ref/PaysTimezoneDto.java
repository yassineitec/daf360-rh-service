package com.daf360.rh.dto.ref;

/**
 * An entity (pays) and the clock its employees work on.
 *
 * @param timezone    IANA id, or null when not configured — the admin panel must show that
 *                    as a warning, because pointage automation is disabled for the entity.
 * @param offsetLabel "UTC+01:00" for the CURRENT instant, computed for display only. Never
 *                    persisted: it changes with DST, which is the whole reason the stored
 *                    value is a zone id and not an offset.
 */
public record PaysTimezoneDto(
        Long   id,
        String isoCode,
        String frenchLabel,
        String timezone,
        String offsetLabel
) {}
