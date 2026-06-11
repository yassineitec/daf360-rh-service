package com.daf360.rh.notification;

import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MailService                           mailService;
    private final AuditService                          auditService;
    private final JdbcTemplate                          jdbc;

    private static final String INSERT_NOTIF_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET())";

    private static final String USERS_BY_ROLE_SQL =
        "SELECT u.id FROM [dbo].[Users] u " +
        "WHERE u.role_id = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL)";

    private static final String USER_EMAIL_BY_ROLE_SQL =
        "SELECT COALESCE(u.username, u.email) " +
        "FROM [dbo].[Users] u " +
        "WHERE u.role_id = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL) " +
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
        try {
            doDispatch(ctx);
        } catch (Exception ex) {
            log.error("NotificationRoutingService failed for event={} pays={}: {}",
                ctx.getEventCode(), ctx.getPaysId(), ex.getMessage(), ex);
        }
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

    private void doDispatch(RoutingContext ctx) {
        // ── Step A: load rule ─────────────────────────────────────────────────
        NotificationRoutingRule rule = loadRule(ctx);
        if (rule == null) {
            log.warn("No routing rule found for event={} pays={} — notification skipped",
                ctx.getEventCode(), ctx.getPaysId());
            return;
        }

        // ── Step B: resolve template variables ────────────────────────────────
        Map<String, String> vars = new HashMap<>(ctx.getTemplateVars());
        vars.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));

        String resolvedTitle   = resolveTemplate(rule.getInappTitleTemplate(), vars);
        String resolvedBody    = resolveTemplate(rule.getInappBodyTemplate(), vars);
        String resolvedSubject = resolveTemplate(rule.getEmailSubjectTemplate(), vars);
        String resolvedHtml    = resolveTemplate(rule.getEmailBodyTemplate(), vars);
        String module          = rule.getEventType().getModule();

        // ── Step C: dispatch in-app ───────────────────────────────────────────
        if (Boolean.TRUE.equals(rule.getSendInapp())) {
            List<Long> recipientIds = resolveInappRecipients(rule, ctx);
            for (Long uid : recipientIds) {
                try {
                    jdbc.update(INSERT_NOTIF_SQL, uid, module, resolvedTitle, resolvedBody);
                } catch (Exception ex) {
                    log.error("Failed to insert notification for userId={}: {}", uid, ex.getMessage());
                }
            }
            log.debug("Dispatched in-app notifications for event={} to {} recipients",
                ctx.getEventCode(), recipientIds.size());
        }

        // ── Step D: dispatch email ────────────────────────────────────────────
        if (Boolean.TRUE.equals(rule.getSendEmail())
                && Boolean.TRUE.equals(rule.getEventType().getSupportsEmail())) {

            EmailAddresses addresses = resolveEmailRecipients(rule, ctx);

            if (!addresses.to.isEmpty()) {
                try {
                    mailService.sendRoutedEmail(
                        addresses.to, addresses.cc, addresses.bcc,
                        resolvedSubject, resolvedHtml
                    );
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
    }

    private List<Long> resolveInappRecipients(NotificationRoutingRule rule, RoutingContext ctx) {
        if (ctx.getDirectUserId() != null) {
            return List.of(ctx.getDirectUserId());
        }
        List<NotificationRoutingRecipient> roleRecipients =
            recipientRepo.findByRuleIdAndIsActiveTrue(rule.getId());
        Set<Long> userIds = new LinkedHashSet<>();
        for (NotificationRoutingRecipient r : roleRecipients) {
            List<Long> ids = jdbc.queryForList(USERS_BY_ROLE_SQL, Long.class,
                r.getRoleId(), ctx.getPaysId());
            userIds.addAll(ids);
        }
        return new ArrayList<>(userIds);
    }

    private EmailAddresses resolveEmailRecipients(NotificationRoutingRule rule, RoutingContext ctx) {
        List<EmailRoutingRecipient> emailRecipients =
            emailRecipientRepo.findByRuleIdAndIsActiveTrue(rule.getId());

        List<String> to = new ArrayList<>(), cc = new ArrayList<>(), bcc = new ArrayList<>();

        for (EmailRoutingRecipient r : emailRecipients) {
            if (!r.getIsActive()) continue;
            List<String> emails = jdbc.queryForList(USER_EMAIL_BY_ROLE_SQL, String.class,
                r.getRoleId(), ctx.getPaysId());
            switch (r.getRecipientField().toUpperCase()) {
                case "TO"  -> to.addAll(emails);
                case "CC"  -> cc.addAll(emails);
                case "BCC" -> bcc.addAll(emails);
            }
        }
        return new EmailAddresses(dedupe(to), dedupe(cc), dedupe(bcc));
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
