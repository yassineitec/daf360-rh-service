package com.daf360.rh.notification;

/**
 * What a notification points AT — the deep-link target's kind.
 *
 * Stored in [dbo].[notifications].entity_type as the enum name; the frontend owns the
 * mapping from a kind to a route. Deliberately NOT a route string: a stored
 * "/rh/candidates/482" couples this service to the Angular router and breaks for good the
 * day a path is renamed, whereas a kind + id is resolved at click time, so a rename fixes
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
    CONTRACT;

    /**
     * Parses a stored/configured value, tolerating null, blank and unknown names.
     *
     * Unknown is not an error: the column is plain varchar(50), and a row written by a
     * future producer (or a hand-edited default) must degrade to "not clickable" rather
     * than break the read path for every other notification in the list.
     */
    public static NotificationEntityType fromNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
