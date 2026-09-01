package com.daf360.rh.notification;

/**
 * How a routing recipient row expands into actual users.
 *
 * Before this existed, the routing engine could only say "every holder of role X", while the
 * hand-written producers resolved by permission — two incompatible targeting models, only one
 * of which was configurable. These three modes are the union, so every notification in the
 * module can be expressed as a routing rule.
 */
public enum NotificationRecipientMode {

    /**
     * Every active holder of the configured role, in the event's entity. The default, and
     * the right answer whenever "whoever is free should act" — an SLA breach, a daily
     * reminder. Narrowing those to one person creates a single point of failure the day
     * they are on leave.
     */
    ALL,

    /**
     * Holders of the PARENT of the configured role (Roles.parent_role_id), in the same
     * entity — i.e. one level up the hierarchy from the role named on the rule.
     *
     * For escalations, where the point is to go above the people who have not acted. Falls
     * back to ALL when the role is top-level, because silence is a worse outcome than a
     * notification sent one level too low.
     */
    MANAGER,

    /**
     * Every active user holding `permission_code`, in the event's entity — regardless of role.
     *
     * Needed to preserve the precision the hardcoded producers had: an offboarding laptop
     * return goes to whoever holds the IT stage permission, which no list of roles expresses
     * as accurately, and which keeps working when roles are reorganised.
     */
    PERMISSION,


    /**
     * The single user the event is ABOUT — `RoutingContext.subjectUserId`.
     *
     * The mode the other three could not express. They all target a CATEGORY (a role, a parent
     * role, a right); "the person concerned" is per-event data, so it can only come from the
     * dispatch. Without it, "tell the employee their own request was approved" had to be
     * hardcoded, and it was: `directUserId` used to RETURN EARLY from recipient resolution,
     * which silenced every configured recipient and made the admin screen's Destinataires
     * section dead for those events. As a mode it is just another recipient row, so an admin
     * can now put "the employee AND their manager" on one rule.
     */
    SUBJECT,

    /**
     * The manager OF the subject: holders of the parent of the subject's own role, in the
     * subject's entity.
     *
     * Distinct from {@link #MANAGER}, which climbs from a role named on the rule. This one
     * climbs from the person the event concerns — "notify X's manager", not "notify the role
     * above the RH officers".
     */
    MANAGER_OF_SUBJECT;

    /** Tolerant parse: an unknown or blank stored value falls back to {@link #ALL}. */
    public static NotificationRecipientMode fromNullable(String value) {
        if (value == null || value.isBlank()) return ALL;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ALL;
        }
    }

    /** True when this mode needs no role and no permission — it resolves from the context. */
    public boolean isContextBased() {
        return this == SUBJECT || this == MANAGER_OF_SUBJECT;
    }
}
