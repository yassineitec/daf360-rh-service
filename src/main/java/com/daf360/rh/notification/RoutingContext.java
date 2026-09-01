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
     * The one user this event is ABOUT: the employee whose request was decided, whose mission
     * was approved, whose onboarding completed.
     *
     * Consumed by the SUBJECT and MANAGER_OF_SUBJECT recipient modes. It used to be called
     * `directUserId` and short-circuited recipient resolution entirely — the notification went
     * to this user and to nobody else, whatever the rule said, which left the admin screen's
     * Destinataires section dead for those events. It is now an INPUT to resolution rather
     * than a replacement for it, so "the employee and their manager" is expressible.
     */
    private final Long subjectUserId;

    /**
     * Template variable substitutions.
     * Keys are placeholder names without braces: "candidateName", "ms365Email", etc.
     * Values are the resolved strings to substitute.
     */
    @Builder.Default
    private final Map<String, String> templateVars = Collections.emptyMap();

    /**
     * Deep-link target kind. Optional — when left null the event type's
     * `default_entity_type` is used, so a call site that always points at the same kind of
     * thing only has to supply the id.
     */
    private final NotificationEntityType entityType;

    /**
     * Deep-link target id. Without it there is no link: a kind on its own resolves to
     * nothing, so the notification is written as non-clickable.
     */
    private final Long entityId;

    /**
     * Permission to use for PERMISSION-mode recipients, overriding the rule's own
     * `permission_code` for this dispatch only.
     *
     * Needed because two offboarding alerts target the department that owns the *task*
     * (OffboardingStagePermissions.forTaskCode) — IT for a laptop, payroll for a final
     * settlement. That varies row by row, so it cannot live on a static rule. Without this
     * the migration onto the routing engine would have replaced precise targeting with
     * "everyone who can manage offboarding", which is the blanket alerting the per-stage
     * permissions were introduced to stop.
     */
    private final String dynamicPermission;
}
