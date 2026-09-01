package com.daf360.rh.notification;

/**
 * The optional deep-link carried by a notification.
 *
 * Either an entity reference (kind + id, resolved to a route by the frontend) or a raw
 * `link` for the cases an entity cannot express — a pre-filtered list, an external URL.
 * `none()` is the honest default: a notification that points nowhere is normal, and is
 * rendered as read-only rather than as a click that goes somewhere useless.
 */
public record NotificationTarget(NotificationEntityType entityType, Long entityId, String link) {

    /** [dbo].[notifications].link is nvarchar(500). */
    private static final int MAX_LINK_LENGTH = 500;

    private static final NotificationTarget NONE = new NotificationTarget(null, null, null);

    /** No deep link — the recipient reads the notification and navigates on their own. */
    public static NotificationTarget none() {
        return NONE;
    }

    /**
     * Points at one entity.
     *
     * A null id collapses to {@link #none()} on purpose: half a reference (a kind with no
     * id) would render as a clickable row that cannot resolve to anything, which is worse
     * than a row that never invited the click.
     */
    public static NotificationTarget of(NotificationEntityType entityType, Long entityId) {
        if (entityType == null || entityId == null) return NONE;
        return new NotificationTarget(entityType, entityId, null);
    }

    /** Escape hatch for targets no entity kind describes. Over-long links are dropped. */
    public static NotificationTarget link(String link) {
        if (link == null || link.isBlank() || link.length() > MAX_LINK_LENGTH) return NONE;
        return new NotificationTarget(null, null, link);
    }

    /** The value written to entity_type — null when this target carries no entity. */
    public String entityTypeName() {
        return entityType != null ? entityType.name() : null;
    }
}
