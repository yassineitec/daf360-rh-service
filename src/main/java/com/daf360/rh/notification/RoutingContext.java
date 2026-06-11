package com.daf360.rh.notification;

import lombok.Builder;
import lombok.Getter;
import java.util.Collections;
import java.util.Map;

/**
 * Value object passed to NotificationRoutingService.resolveAndDispatch().
 * Carries the event code, entity scope, optional direct user override,
 * and template variable map for placeholder substitution.
 *
 * Template placeholders supported (key surrounded by {}):
 *   candidateName, firstName, lastName, ms365Email, entity, date
 * Any key present in templateVars is automatically substituted.
 */
@Builder
@Getter
public class RoutingContext {

    /** Event code matching notification_event_types.event_code */
    private final String eventCode;

    /** pays_id for recipient resolution and entity-specific rule lookup */
    private final Long paysId;

    /**
     * When non-null, bypasses role-based recipient resolution and sends
     * the notification directly to this user only.
     * Used for ONBOARDING_COMPLETED → the new employee.
     */
    private final Long directUserId;

    /**
     * Template variable substitutions.
     * Keys are placeholder names without braces: "candidateName", "ms365Email", etc.
     * Values are the resolved strings to substitute.
     */
    @Builder.Default
    private final Map<String, String> templateVars = Collections.emptyMap();
}
