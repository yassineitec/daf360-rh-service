package com.daf360.rh.notification;

import java.util.Map;

/**
 * Where each absence notification opens — congés, télétravail, autorisations.
 *
 * Per EVENT, not per kind: the same congé sends its approver to the queue they decide from and
 * its employee to « Mes absences », and none of these screens has a /:id page a kind + id
 * could resolve to (which is why they used to be written non-clickable).
 *
 * Paths are the SHELL's: the RH remote is mounted under /rh, self-service under
 * /home/self-service, whose `?tab=` selects the tab. A path renamed on either side must be
 * renamed here; links already written keep the old value.
 */
public final class AbsenceNotificationLinks {

    private static final String MY_ABSENCES = "/home/self-service?tab=absences";

    private static final Map<String, String> LINKS = Map.ofEntries(
            // Congés
            Map.entry("LEAVE_SUBMITTED",        "/rh/conges/inbox"),
            Map.entry("LEAVE_WITHDRAWN",        "/rh/conges/inbox"),
            Map.entry("LEAVE_DECIDED",          MY_ABSENCES),
            Map.entry("LEAVE_CANCELLED",        MY_ABSENCES),
            Map.entry("LEAVE_CHANGE_REQUESTED", "/rh/conges/modifications/manager"),
            Map.entry("LEAVE_CANCEL_REQUESTED", "/rh/conges/modifications/hr"),
            Map.entry("LEAVE_CHANGE_FORWARDED", "/rh/conges/modifications/hr"),
            Map.entry("LEAVE_CHANGE_DECIDED",   MY_ABSENCES),
            // Télétravail
            Map.entry("TELETRAVAIL_SUBMITTED",  "/rh/teletravail/queue"),
            Map.entry("TELETRAVAIL_WITHDRAWN",  "/rh/teletravail/queue"),
            Map.entry("TELETRAVAIL_DECIDED",    MY_ABSENCES),
            Map.entry("TELETRAVAIL_CANCELLED",  MY_ABSENCES),
            // Autorisations
            Map.entry("AUTORISATION_SUBMITTED", "/rh/autorisations/queue"),
            Map.entry("AUTORISATION_WITHDRAWN", "/rh/autorisations/queue"),
            Map.entry("AUTORISATION_DECIDED",   MY_ABSENCES),
            Map.entry("AUTORISATION_CANCELLED", MY_ABSENCES));

    private AbsenceNotificationLinks() {}

    /** The page for this event, or null — a notification without a link is still read. */
    public static String forEvent(String eventCode) {
        return eventCode == null ? null : LINKS.get(eventCode);
    }
}
