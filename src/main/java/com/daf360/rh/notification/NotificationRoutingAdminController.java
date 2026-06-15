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
//@PreAuthorize("hasPermission(null, 'ADMIN_NOTIFICATIONS')")
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
     * Body: { "roleId": 5 }
     */
    @PostMapping("/notification-rules/{ruleId}/inapp-recipients")
    public ResponseEntity<NotificationRoutingAdminService.RecipientItem> addInappRecipient(
            @PathVariable Long ruleId,
            @RequestBody Map<String, Long> body,
            Authentication auth) {
        Long roleId = body.get("roleId");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(notifAdminService.addInappRecipient(ruleId, roleId, actorId(auth)));
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
     * Body: { "roleId": 5, "field": "TO" }
     */
    @PostMapping("/notification-rules/{ruleId}/email-recipients")
    public ResponseEntity<NotificationRoutingAdminService.RecipientItem> addEmailRecipient(
            @PathVariable Long ruleId,
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long   roleId = body.get("roleId") != null ? Long.valueOf(body.get("roleId").toString()) : null;
        String field  = body.get("field") != null ? body.get("field").toString() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(notifAdminService.addEmailRecipient(ruleId, roleId, field, actorId(auth)));
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

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
