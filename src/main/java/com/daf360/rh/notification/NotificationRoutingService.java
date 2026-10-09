package com.daf360.rh.notification;

import com.daf360.rh.common.UserScope;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRoutingService {

    private final NotificationRoutingRuleRepository     ruleRepo;
    private final NotificationRoutingRecipientRepository recipientRepo;
    private final EmailRoutingRecipientRepository       emailRecipientRepo;
    private final InAppNotifier                         inAppNotifier;
    private final MailService                           mailService;
    private final AuditService                          auditService;
    private final JdbcTemplate                          jdbc;

    // ── Recipient resolution ──────────────────────────────────────────────────
    // Three targeting modes, all scoped to the event's entity. Every query filters on
    // pays_id: the same role and the same permission exist in every country, and a
    // hierarchy or a duty roster that crosses entities is not one.

    /** ALL — every active holder of the role. */
    private static final String USERS_BY_ROLE_SQL =
        "SELECT u.id FROM [dbo].[Users] u " +
        "WHERE u.role_id = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u");

    /** MANAGER — holders of the PARENT of the given role (Roles.parent_role_id). */
    private static final String USERS_BY_PARENT_ROLE_SQL =
        "SELECT u.id FROM [dbo].[Users] u " +
        "WHERE u.pays_id = ? AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u") + " " +
        "AND u.role_id = (" +
        "  SELECT pr.id FROM [dbo].[Roles] cr " +
        "  JOIN [dbo].[Roles] pr ON pr.id = cr.parent_role_id " +
        "  WHERE cr.id = ? AND (pr.deleted = 0 OR pr.deleted IS NULL))";

    /** PERMISSION — every active user whose role carries the permission. */
    private static final String USERS_BY_PERMISSION_SQL =
        "SELECT DISTINCT u.id FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON rp.role_id = u.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u");

    /** SUBJECT — the one user the event is about, looked up only to confirm they are active. */
    private static final String SUBJECT_USER_SQL =
        "SELECT u.id FROM [dbo].[Users] u " +
        "WHERE u.id = ? AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u");

    private static final String SUBJECT_EMAIL_SQL =
        "SELECT COALESCE(u.username, u.email) FROM [dbo].[Users] u " +
        "WHERE u.id = ? AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u") + " " +
        "AND COALESCE(u.username, u.email) IS NOT NULL";

    /**
     * MANAGER_OF_SUBJECT — holders of the parent of the SUBJECT's own role, in the subject's
     * entity. One parameter: everything is derived from the subject.
     */
    private static final String SUBJECT_MANAGERS_SQL =
        "SELECT m.id FROM [dbo].[Users] s " +
        "JOIN [dbo].[Roles] sr ON sr.id = s.role_id " +
        "JOIN [dbo].[Roles] pr ON pr.id = sr.parent_role_id " +
        "                     AND (pr.deleted = 0 OR pr.deleted IS NULL) " +
        "JOIN [dbo].[Users] m ON m.role_id = pr.id AND m.pays_id = s.pays_id " +
        "                     AND (m.isActive = 1 OR m.isActive IS NULL) AND " + UserScope.realPeople("m") + " " +
        "WHERE s.id = ?";

    private static final String SUBJECT_MANAGERS_EMAIL_SQL =
        "SELECT COALESCE(m.username, m.email) FROM [dbo].[Users] s " +
        "JOIN [dbo].[Roles] sr ON sr.id = s.role_id " +
        "JOIN [dbo].[Roles] pr ON pr.id = sr.parent_role_id " +
        "                     AND (pr.deleted = 0 OR pr.deleted IS NULL) " +
        "JOIN [dbo].[Users] m ON m.role_id = pr.id AND m.pays_id = s.pays_id " +
        "                     AND (m.isActive = 1 OR m.isActive IS NULL) AND " + UserScope.realPeople("m") + " " +
        "WHERE s.id = ? AND COALESCE(m.username, m.email) IS NOT NULL";

    private static final String USER_EMAIL_BY_ROLE_SQL =
        "SELECT COALESCE(u.username, u.email) " +
        "FROM [dbo].[Users] u " +
        "WHERE u.role_id = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u") + " " +
        "AND COALESCE(u.username, u.email) IS NOT NULL";

    private static final String USER_EMAIL_BY_PARENT_ROLE_SQL =
        "SELECT COALESCE(u.username, u.email) " +
        "FROM [dbo].[Users] u " +
        "WHERE u.pays_id = ? AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u") + " " +
        "AND COALESCE(u.username, u.email) IS NOT NULL " +
        "AND u.role_id = (" +
        "  SELECT pr.id FROM [dbo].[Roles] cr " +
        "  JOIN [dbo].[Roles] pr ON pr.id = cr.parent_role_id " +
        "  WHERE cr.id = ? AND (pr.deleted = 0 OR pr.deleted IS NULL))";

    private static final String USER_EMAIL_BY_PERMISSION_SQL =
        "SELECT DISTINCT COALESCE(u.username, u.email) " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON rp.role_id = u.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) AND " + UserScope.realPeople("u") + " " +
        "AND COALESCE(u.username, u.email) IS NOT NULL";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolves routing rules from the DB and dispatches in-app notifications
     * and/or emails. Runs asynchronously so it never blocks the caller.
     * All exceptions are caught internally — the caller must not fail because
     * notification dispatch failed.
     */
    @Async
    public void resolveAndDispatch(RoutingContext ctx) {
        dispatchNow(ctx);
    }

    /**
     * Dispatches on the CALLING thread and reports what actually went out.
     *
     * For callers that must know whether anyone was reached — the lifecycle alert job marks an
     * alert sent only when this says so. Through the async entry point an empty audience, a
     * missing rule or a crash were all indistinguishable from success, so a due alert was
     * flagged sent and never retried. Never throws.
     */
    public DispatchResult dispatchNow(RoutingContext ctx) {
        try {
            return doDispatch(ctx);
        } catch (Exception ex) {
            log.error("NotificationRoutingService failed for event={} pays={}: {}",
                ctx.getEventCode(), ctx.getPaysId(), ex.getMessage(), ex);
            return DispatchResult.FAILED;
        }
    }

    /**
     * Dispatches once the caller's transaction has COMMITTED; immediately when there is none.
     *
     * A business service that raised its event inside its own transaction used to hand it to
     * the async pool straight away, so the notification and the e-mail could leave before the
     * commit — and still leave when the commit then failed, announcing a request that does not
     * exist. A rolled-back transaction now sends nothing.
     */
    public void dispatchAfterCommit(RoutingContext ctx) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Through the proxy so @Async applies: the caller's thread is returning
                    // its HTTP response and must not wait on SMTP.
                    self().resolveAndDispatch(ctx);
                }
            });
        } else {
            self().resolveAndDispatch(ctx);
        }
    }

    /** What one dispatch delivered. {@code delivered()} is true when at least one channel reached someone. */
    public record DispatchResult(boolean ruleFound, int inappWritten, int emailRecipients, boolean failed) {
        static final DispatchResult NO_RULE = new DispatchResult(false, 0, 0, false);
        static final DispatchResult FAILED  = new DispatchResult(false, 0, 0, true);

        public boolean delivered() {
            return inappWritten > 0 || emailRecipients > 0;
        }
    }

    // Self-reference so dispatchAfterCommit reaches the @Async proxy rather than `this`.
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private NotificationRoutingService selfProxy;

    private NotificationRoutingService self() {
        return selfProxy != null ? selfProxy : this;
    }

    // ── Private engine ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    protected NotificationRoutingRule loadRule(RoutingContext ctx) {
        // Entity-specific rule takes priority over global (pays_id IS NULL)
        return ruleRepo
            .findByEventTypeEventCodeAndPaysIdAndIsActiveTrue(ctx.getEventCode(), ctx.getPaysId())
            .or(() -> ruleRepo.findByEventTypeEventCodeAndPaysIdIsNullAndIsActiveTrue(ctx.getEventCode()))
            .orElse(null);
    }

    private DispatchResult doDispatch(RoutingContext ctx) {
        // ── Step A: load rule ─────────────────────────────────────────────────
        NotificationRoutingRule rule = loadRule(ctx);
        if (rule == null) {
            log.warn("No routing rule found for event={} pays={} — notification skipped",
                ctx.getEventCode(), ctx.getPaysId());
            return DispatchResult.NO_RULE;
        }
        int inappWritten = 0;
        int emailRecipients = 0;

        // ── Step B: resolve template variables ────────────────────────────────
        Map<String, String> vars = new HashMap<>(ctx.getTemplateVars());
        vars.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));

        String resolvedTitle   = resolveTemplate(rule.getInappTitleTemplate(), vars);
        String resolvedBody    = resolveTemplate(rule.getInappBodyTemplate(), vars);
        String resolvedSubject = resolveTemplate(rule.getEmailSubjectTemplate(), vars);
        String resolvedHtml    = resolveTemplate(rule.getEmailBodyTemplate(), vars);
        String module          = rule.getEventType().getModule();

        // ── Step C: dispatch in-app ───────────────────────────────────────────
        NotificationTarget target = resolveTarget(rule, ctx);

        if (Boolean.TRUE.equals(rule.getSendInapp())) {
            List<Long> recipientIds = new ArrayList<>(resolveInappRecipients(rule, ctx));
            if (ctx.getActorUserId() != null) {
                recipientIds.remove(ctx.getActorUserId());   // remove(Object), not remove(int)
            }
            inappWritten = inAppNotifier.notifyUsers(
                recipientIds, module, resolvedTitle, resolvedBody, target);
            log.debug("Dispatched in-app notifications for event={} to {}/{} recipients",
                ctx.getEventCode(), inappWritten, recipientIds.size());
        }

        // ── Step D: dispatch email ────────────────────────────────────────────
        if (Boolean.TRUE.equals(rule.getSendEmail())
                && !Boolean.TRUE.equals(rule.getEventType().getSupportsEmail())) {
            // The admin can tick "e-mail" on a rule whose event type has no e-mail support;
            // that used to be skipped without a trace.
            log.warn("Rule {} has send_email=1 but event type {} has supports_email=0 — e-mail skipped",
                rule.getId(), ctx.getEventCode());
        }
        if (Boolean.TRUE.equals(rule.getSendEmail())
                && Boolean.TRUE.equals(rule.getEventType().getSupportsEmail())) {

            EmailAddresses addresses = withoutActor(resolveEmailRecipients(rule, ctx), ctx);

            if (!addresses.to.isEmpty()) {
                try {
                    mailService.sendRoutedEmail(
                        addresses.to, addresses.cc, addresses.bcc,
                        resolvedSubject, resolvedHtml
                    );
                    emailRecipients = addresses.to.size();
                    log.debug("Dispatched email for event={} to TO={}", ctx.getEventCode(), addresses.to.size());
                } catch (Exception ex) {
                    log.error("Email dispatch failed for event={}: {}", ctx.getEventCode(), ex.getMessage());
                }
            } else {
                log.warn("No TO recipients found for event={} pays={} — email not sent",
                    ctx.getEventCode(), ctx.getPaysId());
            }
        }

        // ── Step E: audit ─────────────────────────────────────────────────────
        auditService.log(
            "SYSTEM", "NOTIFICATION_DISPATCHED", "NOTIFICATION_EVENT", null,
            null,
            "event=" + ctx.getEventCode() + " pays=" + ctx.getPaysId()
        );
        return new DispatchResult(true, inappWritten, emailRecipients, false);
    }

    private static final String ACTOR_EMAIL_SQL =
        "SELECT COALESCE(u.username, u.email) FROM [dbo].[Users] u WHERE u.id = ?";

    /** Drops the actor's own address from every field — the e-mail twin of the in-app exclusion. */
    private EmailAddresses withoutActor(EmailAddresses a, RoutingContext ctx) {
        if (ctx.getActorUserId() == null) return a;
        List<String> mine;
        try {
            mine = jdbc.queryForList(ACTOR_EMAIL_SQL, String.class, ctx.getActorUserId());
        } catch (Exception ex) {
            return a;
        }
        if (mine.isEmpty() || mine.get(0) == null) return a;
        String me = mine.get(0);
        java.util.function.Predicate<String> notMe = s -> !me.equalsIgnoreCase(s);
        return new EmailAddresses(
            a.to.stream().filter(notMe).toList(),
            a.cc.stream().filter(notMe).toList(),
            a.bcc.stream().filter(notMe).toList());
    }

    /**
     * Resolves the deep-link target for a dispatch.
     *
     * The kind comes from the call site when it knows it, otherwise from the event type's
     * configured `default_entity_type`; the id only ever comes from the call site. No id
     * means no link — see NotificationTarget.of.
     */
    private NotificationTarget resolveTarget(NotificationRoutingRule rule, RoutingContext ctx) {
        if (ctx.getEntityId() == null) return NotificationTarget.none();
        NotificationEntityType type = ctx.getEntityType() != null
            ? ctx.getEntityType()
            : NotificationEntityType.fromNullable(rule.getEventType().getDefaultEntityType());
        return NotificationTarget.of(type, ctx.getEntityId());
    }

    private List<Long> resolveInappRecipients(NotificationRoutingRule rule, RoutingContext ctx) {
        List<NotificationRoutingRecipient> recipients =
            recipientRepo.findByRuleIdAndIsActiveTrue(rule.getId());

        Set<Long> userIds = new LinkedHashSet<>();
        for (NotificationRoutingRecipient r : recipients) {
            userIds.addAll(resolveOne(r, ctx, rule));
        }

        // Safety net for a subject-driven event whose rule has no recipients yet — the state
        // between deploying this code and applying V93. Without it, removing the old
        // directUserId short-circuit would have silently stopped telling employees that their
        // own request was decided. Logged, because a configured rule should never need it.
        if (userIds.isEmpty() && recipients.isEmpty() && ctx.getSubjectUserId() != null) {
            log.warn("Rule {} for event={} has no recipients; falling back to the subject "
                + "(userId={}). Add a SUBJECT recipient to make this explicit.",
                rule.getId(), ctx.getEventCode(), ctx.getSubjectUserId());
            return List.of(ctx.getSubjectUserId());
        }

        if (userIds.isEmpty()) {
            // A rule can be active, correct and still reach nobody: recipients are filtered
            // by pays_id, so a null or mismatched entity resolves to an empty list and the
            // notification simply never appears. This used to return silently.
            log.warn("No in-app recipients resolved for event={} pays={} rule={} — " +
                "check the rule's recipients and the users' pays_id",
                ctx.getEventCode(), ctx.getPaysId(), rule.getId());
        }
        return new ArrayList<>(userIds);
    }

    /**
     * Expands one recipient row into user ids according to its mode.
     *
     * MANAGER falls back to ALL when the configured role has no parent: escalating from a
     * top-level role has nowhere to go, and sending it one level too low beats sending it
     * nowhere. The fallback is logged so a mis-configured hierarchy is visible rather than
     * silently absorbed.
     */
    private List<Long> resolveOne(NotificationRoutingRecipient r, RoutingContext ctx,
                                  NotificationRoutingRule rule) {
        NotificationRecipientMode mode = NotificationRecipientMode.fromNullable(r.getRecipientMode());
        try {
            switch (mode) {
                case PERMISSION -> {
                    // The context wins when set: a per-task permission is more specific than
                    // whatever the rule carries.
                    String permission = effectivePermission(ctx, r.getPermissionCode());
                    if (permission == null) {
                        log.warn("Recipient {} on rule {} is PERMISSION mode with no permission — skipped",
                            r.getId(), rule.getId());
                        return List.of();
                    }
                    return jdbc.queryForList(USERS_BY_PERMISSION_SQL, Long.class,
                        permission, ctx.getPaysId());
                }
                case SUBJECT -> {
                    if (ctx.getSubjectUserId() == null) {
                        log.warn("Recipient {} on rule {} is SUBJECT mode but event={} carries no "
                            + "subjectUserId — skipped", r.getId(), rule.getId(), ctx.getEventCode());
                        return List.of();
                    }
                    // Looked up rather than trusted: an inactive user must not be notified,
                    // and the id comes from business data that may be stale.
                    return jdbc.queryForList(SUBJECT_USER_SQL, Long.class, ctx.getSubjectUserId());
                }
                case MANAGER_OF_SUBJECT -> {
                    if (ctx.getSubjectUserId() == null) return List.of();
                    List<Long> managers = jdbc.queryForList(SUBJECT_MANAGERS_SQL, Long.class,
                        ctx.getSubjectUserId());
                    if (managers.isEmpty()) {
                        // No fallback to the subject here: telling someone "your manager was
                        // informed" by informing them instead would be actively misleading.
                        log.warn("MANAGER_OF_SUBJECT found no manager for userId={} (event={}) — "
                            + "their role may be top-level or have no active holder above it",
                            ctx.getSubjectUserId(), ctx.getEventCode());
                    }
                    return managers;
                }
                case MANAGER -> {
                    if (r.getRoleId() == null) return List.of();
                    List<Long> managers = jdbc.queryForList(USERS_BY_PARENT_ROLE_SQL, Long.class,
                        ctx.getPaysId(), r.getRoleId());
                    if (!managers.isEmpty()) return managers;
                    log.warn("MANAGER mode found no parent-role holder for roleId={} pays={} " +
                        "(event={}) — falling back to the role itself",
                        r.getRoleId(), ctx.getPaysId(), ctx.getEventCode());
                    return jdbc.queryForList(USERS_BY_ROLE_SQL, Long.class,
                        r.getRoleId(), ctx.getPaysId());
                }
                default -> {
                    if (r.getRoleId() == null) return List.of();
                    return jdbc.queryForList(USERS_BY_ROLE_SQL, Long.class,
                        r.getRoleId(), ctx.getPaysId());
                }
            }
        } catch (Exception ex) {
            // One bad recipient row must not silence the whole notification.
            log.error("Failed to resolve recipient {} (mode={}) on rule {}: {}",
                r.getId(), mode, rule.getId(), ex.getMessage());
            return List.of();
        }
    }

    private EmailAddresses resolveEmailRecipients(NotificationRoutingRule rule, RoutingContext ctx) {
        List<EmailRoutingRecipient> emailRecipients =
            emailRecipientRepo.findByRuleIdAndIsActiveTrue(rule.getId());

        List<String> to = new ArrayList<>(), cc = new ArrayList<>(), bcc = new ArrayList<>();

        for (EmailRoutingRecipient r : emailRecipients) {
            if (!Boolean.TRUE.equals(r.getIsActive())) continue;
            List<String> emails = resolveOneEmail(r, ctx, rule);
            String field = r.getRecipientField() != null ? r.getRecipientField().toUpperCase() : "TO";
            switch (field) {
                case "CC"  -> cc.addAll(emails);
                case "BCC" -> bcc.addAll(emails);
                default    -> to.addAll(emails);
            }
        }
        return new EmailAddresses(dedupe(to), dedupe(cc), dedupe(bcc));
    }

    /** Email counterpart of {@link #resolveOne}, mode for mode. */
    private List<String> resolveOneEmail(EmailRoutingRecipient r, RoutingContext ctx,
                                         NotificationRoutingRule rule) {
        NotificationRecipientMode mode = NotificationRecipientMode.fromNullable(r.getRecipientMode());
        try {
            switch (mode) {
                case PERMISSION -> {
                    String permission = effectivePermission(ctx, r.getPermissionCode());
                    if (permission == null) return List.of();
                    return jdbc.queryForList(USER_EMAIL_BY_PERMISSION_SQL, String.class,
                        permission, ctx.getPaysId());
                }
                case SUBJECT -> {
                    if (ctx.getSubjectUserId() == null) return List.of();
                    return jdbc.queryForList(SUBJECT_EMAIL_SQL, String.class, ctx.getSubjectUserId());
                }
                case MANAGER_OF_SUBJECT -> {
                    if (ctx.getSubjectUserId() == null) return List.of();
                    return jdbc.queryForList(SUBJECT_MANAGERS_EMAIL_SQL, String.class,
                        ctx.getSubjectUserId());
                }
                case MANAGER -> {
                    if (r.getRoleId() == null) return List.of();
                    List<String> managers = jdbc.queryForList(USER_EMAIL_BY_PARENT_ROLE_SQL, String.class,
                        ctx.getPaysId(), r.getRoleId());
                    if (!managers.isEmpty()) return managers;
                    return jdbc.queryForList(USER_EMAIL_BY_ROLE_SQL, String.class,
                        r.getRoleId(), ctx.getPaysId());
                }
                default -> {
                    if (r.getRoleId() == null) return List.of();
                    return jdbc.queryForList(USER_EMAIL_BY_ROLE_SQL, String.class,
                        r.getRoleId(), ctx.getPaysId());
                }
            }
        } catch (Exception ex) {
            log.error("Failed to resolve email recipient {} (mode={}) on rule {}: {}",
                r.getId(), mode, rule.getId(), ex.getMessage());
            return List.of();
        }
    }


    /** Context permission if the dispatch supplied one, else the rule's own. */
    private String effectivePermission(RoutingContext ctx, String rulePermission) {
        String dynamic = ctx.getDynamicPermission();
        if (dynamic != null && !dynamic.isBlank()) return dynamic;
        return (rulePermission != null && !rulePermission.isBlank()) ? rulePermission : null;
    }
    private List<String> dedupe(List<String> list) {
        return list.stream().distinct().filter(s -> s != null && !s.isBlank()).collect(Collectors.toList());
    }

    private String resolveTemplate(String template, Map<String, String> vars) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private record EmailAddresses(List<String> to, List<String> cc, List<String> bcc) {}
}
