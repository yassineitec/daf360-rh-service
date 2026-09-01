package com.daf360.rh.notification;

import com.daf360.rh.notification.dto.UpdateRoutingRuleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/admin")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'ADMIN_NOTIFICATIONS')")
public class NotificationRoutingAdminController {

    private final NotificationRoutingAdminService notifAdminService;

    // ── Event types ───────────────────────────────────────────────────────────

    /**
     * GET /api/hr/admin/notification-event-types
     * Returns all active event types with routing rule summary counts.
     */
    @GetMapping("/notification-event-types")
    public ResponseEntity<List<NotificationRoutingAdminService.NotificationEventTypeResponse>> getEventTypes() {
        return ResponseEntity.ok(notifAdminService.getEventTypes());
    }

    // ── Routing rules ─────────────────────────────────────────────────────────

    /**
     * GET /api/hr/admin/notification-rules/{eventTypeId}
     * Returns full routing rule detail for the given event type.
     */
    @GetMapping("/notification-rules/{eventTypeId}")
    public ResponseEntity<NotificationRoutingAdminService.RoutingRuleDetail> getRoutingRule(
            @PathVariable Long eventTypeId) {
        return ResponseEntity.ok(notifAdminService.getRoutingRule(eventTypeId));
    }

    /**
     * PATCH /api/hr/admin/notification-rules/{ruleId}
     * Partially updates a routing rule (PATCH semantics — only non-null fields applied).
     */
    @PatchMapping("/notification-rules/{ruleId}")
    public ResponseEntity<NotificationRoutingRule> updateRoutingRule(
            @PathVariable Long ruleId,
            @RequestBody UpdateRoutingRuleRequest dto,
            Authentication auth) {
        return ResponseEntity.ok(notifAdminService.updateRoutingRule(ruleId, dto, actorId(auth)));
    }

    // ── In-app recipients ─────────────────────────────────────────────────────

    /**
     * POST /api/hr/admin/notification-rules/{ruleId}/inapp-recipients
     * Body: { "roleId": 5, "mode": "ALL" } or { "mode": "PERMISSION", "permissionCode": "..." }
     */
    @PostMapping("/notification-rules/{ruleId}/inapp-recipients")
    public ResponseEntity<NotificationRoutingAdminService.RecipientItem> addInappRecipient(
            @PathVariable Long ruleId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notifAdminService.addInappRecipient(
                ruleId, asLong(body.get("roleId")), asString(body.get("mode")),
                asString(body.get("permissionCode")), actorId(auth)));
    }

    /**
     * DELETE /api/hr/admin/notification-rules/inapp-recipients/{id}
     */
    @DeleteMapping("/notification-rules/inapp-recipients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInappRecipient(
            @PathVariable Long id,
            Authentication auth) {
        notifAdminService.removeInappRecipient(id, actorId(auth));
    }

    // ── Email recipients ──────────────────────────────────────────────────────

    /**
     * POST /api/hr/admin/notification-rules/{ruleId}/email-recipients
     * Body: { "roleId": 5, "field": "TO", "mode": "ALL" }
     *    or { "field": "CC", "mode": "PERMISSION", "permissionCode": "..." }
     */
    @PostMapping("/notification-rules/{ruleId}/email-recipients")
    public ResponseEntity<NotificationRoutingAdminService.RecipientItem> addEmailRecipient(
            @PathVariable Long ruleId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notifAdminService.addEmailRecipient(
                ruleId, asLong(body.get("roleId")), asString(body.get("field")),
                asString(body.get("mode")), asString(body.get("permissionCode")), actorId(auth)));
    }

    /**
     * GET /api/hr/admin/notification-permissions
     * The permission codes selectable for a PERMISSION recipient, grouped as the catalogue
     * groups them so the picker can show sections rather than 80 flat codes.
     */
    @GetMapping("/notification-permissions")
    public ResponseEntity<List<NotificationRoutingAdminService.PermissionOption>> getAssignablePermissions() {
        return ResponseEntity.ok(notifAdminService.getAssignablePermissions());
    }

    /**
     * DELETE /api/hr/admin/notification-rules/email-recipients/{id}
     */
    @DeleteMapping("/notification-rules/email-recipients/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeEmailRecipient(
            @PathVariable Long id,
            Authentication auth) {
        notifAdminService.removeEmailRecipient(id, actorId(auth));
    }


    /**
     * POST /api/hr/admin/notification-rules/event-type/{eventTypeId}
     * Creates the (global) routing rule for an event type that has none yet.
     * Returns the same detail payload the editor loads, so the UI can open straight into it.
     */
    @PostMapping("/notification-rules/event-type/{eventTypeId}")
    public ResponseEntity<NotificationRoutingAdminService.RoutingRuleDetail> createRoutingRule(
            @PathVariable Long eventTypeId,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(notifAdminService.createRoutingRule(eventTypeId, actorId(auth)));
    }

    /**
     * PATCH /api/hr/admin/notification-event-types/{id}/entity-type
     * Body: { "defaultEntityType": "CANDIDATE" }  (null clears it)
     *
     * The deep-link kind lives on the EVENT TYPE, not the rule: an event always points at
     * the same sort of thing whatever an entity's routing says. Until now it could only be
     * set in SQL.
     */
    @PatchMapping("/notification-event-types/{id}/entity-type")
    public ResponseEntity<Void> setDefaultEntityType(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        notifAdminService.setDefaultEntityType(id, body.get("defaultEntityType"), actorId(auth));
        return ResponseEntity.noContent().build();
    }
    // ── Test dispatch ─────────────────────────────────────────────────────────

    /**
     * POST /api/hr/admin/notification-rules/{ruleId}/test?pays={paysId}
     * Simulates dispatch without sending real notifications or emails.
     */
    @PostMapping("/notification-rules/{ruleId}/test")
    public ResponseEntity<NotificationRoutingAdminService.TestDispatchResult> testDispatch(
            @PathVariable Long ruleId,
            @RequestParam Long pays) {
        return ResponseEntity.ok(notifAdminService.testDispatch(ruleId, pays));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private static Long asLong(Object v) {
        return v != null ? Long.valueOf(v.toString()) : null;
    }

    private static String asString(Object v) {
        return v != null ? v.toString() : null;
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
