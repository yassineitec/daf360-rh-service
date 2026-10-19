package com.daf360.rh.notification;

/**
 * What a notification points AT — the deep-link target's kind.
 *
 * Stored in [dbo].[notifications].entity_type as the enum name; the frontend
 * owns the
 * mapping from a kind to a route. Deliberately NOT a route string: a stored
 * "/rh/candidates/482" couples this service to the Angular router and breaks
 * for good the
 * day a path is renamed, whereas a kind + id is resolved at click time, so a
 * rename fixes
 * every historical row at once.
 *
 * Only add a value here once a page exists that can receive it.
 */
public enum NotificationEntityType {

    CANDIDATE,
    EMPLOYEE_PROFILE,
    OFFBOARDING,
    ONBOARDING,
    IT_PROVISIONING,
    REQUEST,
    RECRUITMENT_DEMAND,
    CONTRACT,
    /**
     * An avance sur salaire (V103) — resolved by the frontend to the desk that can
     * act on it.
     */
    SALARY_ADVANCE,
    /**
     * A congé (V113). One kind for the request and for any modification proposal
     * filed
     * against it: the proposal is only ever read beside the congé it contests, so a
     * recipient
     * landing on the congé lands in the right place either way.
     */
    LEAVE,
    /** A short paid absence in minutes (V114) — its own kind, its own screens. */
    AUTORISATION,
    /** A day or half day of remote work (V116) — its own kind, its own screens. */
    TELETRAVAIL,
    /** A CIVP → CDI conversion request (V119) — /rh/cdi-conversions/{id}. */
    CDI_CONVERSION;

    /**
     * Parses a stored/configured value, tolerating null, blank and unknown names.
     *
     * Unknown is not an error: the column is plain varchar(50), and a row written
     * by a
     * future producer (or a hand-edited default) must degrade to "not clickable"
     * rather
     * than break the read path for every other notification in the list.
     */
    public static NotificationEntityType fromNullable(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
