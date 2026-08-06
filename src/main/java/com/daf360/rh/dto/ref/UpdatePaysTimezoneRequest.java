package com.daf360.rh.dto.ref;

import lombok.Data;

/**
 * Body of PUT /api/hr/ref/pays/{id}/timezone.
 *
 * A null/blank timezone clears the entity's zone, which disables its pointage automation.
 * Deliberately allowed: it is better to have "not configured" be expressible than to have an
 * admin unable to undo a wrong entry.
 */
@Data
public class UpdatePaysTimezoneRequest {
    /** IANA identifier (e.g. Africa/Tunis). Not an offset — see PaysTimezoneService. */
    private String timezone;
}
