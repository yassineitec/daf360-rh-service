package com.daf360.rh.notification;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.dto.UpdateRoutingRuleRequest;
import com.daf360.rh.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationRoutingAdminService {

    private final NotificationRoutingRuleRepository     ruleRepo;
    private final NotificationRoutingRecipientRepository inappRecipRepo;
    private final EmailRoutingRecipientRepository       emailRecipRepo;
    private final AuditService                          auditService;
    private final JdbcTemplate                          jdbc;

    // ── SQL constants ─────────────────────────────────────────────────────────

    private static final String ROLES_SQL =
        "SELECT id, frenchName FROM [dbo].[Roles] " +
        "WHERE (deleted=0 OR deleted IS NULL) ORDER BY frenchName ASC";

    private static final String USERS_BY_ROLE_SQL =
        "SELECT u.id, u.fullName, COALESCE(u.username, u.email) as email " +
        "FROM [dbo].[Users] u " +
        "WHERE u.role_id=? AND u.pays_id=? " +
        "AND (u.isActive=1 OR u.isActive IS NULL)";

    private static final String ROLE_NAME_SQL =
        "SELECT frenchName FROM [dbo].[Roles] WHERE id=?";

    private static final String EVENT_TYPES_SQL =
        "SELECT et.id, et.event_code, et.label_fr, et.label_en, et.module, " +
        "       et.supports_email, et.is_system, " +
        "       rr.id as rule_id, rr.send_inapp, rr.send_email " +
        "FROM notification_event_types et " +
        "LEFT JOIN notification_routing_rules rr " +
        "       ON rr.event_type_id=et.id AND rr.is_active=1 " +
        "WHERE et.is_active=1 " +
        "ORDER BY et.module, et.label_fr";

    private static final String INAPP_RECIP_COUNT_SQL =
        "SELECT COUNT(*) FROM notification_routing_recipients " +
        "WHERE routing_rule_id=? AND is_active=1";

    private static final String EMAIL_TO_COUNT_SQL =
        "SELECT COUNT(*) FROM email_routing_recipients " +
        "WHERE routing_rule_id=? AND recipient_field='TO' AND is_active=1";

    private static final String INAPP_RECIP_SQL =
        "SELECT nr.id, nr.role_id, r.frenchName " +
        "FROM notification_routing_recipients nr " +
        "JOIN [dbo].[Roles] r ON r.id=nr.role_id " +
        "WHERE nr.routing_rule_id=? AND nr.is_active=1";

    private static final String EMAIL_RECIP_SQL =
        "SELECT er.id, er.role_id, r.frenchName, er.recipient_field " +
        "FROM email_routing_recipients er " +
        "JOIN [dbo].[Roles] r ON r.id=er.role_id " +
        "WHERE er.routing_rule_id=? AND er.is_active=1";

    // ── Public methods ────────────────────────────────────────────────────────

    /**
     * 1. Returns all active event types with their routing rule summary counts.
     */
    @Transactional(readOnly = true)
    public List<NotificationEventTypeResponse> getEventTypes() {
        return jdbc.query(EVENT_TYPES_SQL, rs -> {
            List<NotificationEventTypeResponse> list = new ArrayList<>();
            while (rs.next()) {
                Long ruleId = rs.getObject("rule_id") != null
                        ? rs.getLong("rule_id") : null;

                int inappCount  = 0;
                int emailToCount = 0;
                if (ruleId != null) {
                    inappCount   = countById(INAPP_RECIP_COUNT_SQL, ruleId);
                    emailToCount = countById(EMAIL_TO_COUNT_SQL, ruleId);
                }

                list.add(NotificationEventTypeResponse.builder()
                        .id(rs.getLong("id"))
                        .eventCode(rs.getString("event_code"))
                        .labelFr(rs.getString("label_fr"))
                        .labelEn(rs.getString("label_en"))
                        .module(rs.getString("module"))
                        .supportsEmail(rs.getBoolean("supports_email"))
                        .isSystem(rs.getBoolean("is_system"))
                        .ruleId(ruleId)
                        .sendInapp(ruleId != null ? rs.getBoolean("send_inapp") : null)
                        .sendEmail(ruleId != null ? rs.getBoolean("send_email") : null)
                        .inappRecipientCount(inappCount)
                        .emailToCount(emailToCount)
                        .build());
            }
            return list;
        });
    }

    /**
     * 2. Returns full routing rule detail for a given event type id.
     */
    @Transactional(readOnly = true)
    public RoutingRuleDetail getRoutingRule(Long eventTypeId) {
        NotificationRoutingRule rule = ruleRepo
                .findByEventTypeIdAndIsActiveTrue(eventTypeId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "No active routing rule for eventTypeId=" + eventTypeId));

        // Load in-app recipients
        List<RecipientItem> inappRecipients = jdbc.query(
                INAPP_RECIP_SQL,
                (rs, rowNum) -> new RecipientItem(
                        rs.getLong("id"),
                        rs.getLong("role_id"),
                        rs.getString("frenchName")),
                rule.getId());

        // Load email recipients grouped by recipientField
        List<RecipientItemWithField> emailRecipients = jdbc.query(
                EMAIL_RECIP_SQL,
                (rs, rowNum) -> new RecipientItemWithField(
                        rs.getLong("id"),
                        rs.getLong("role_id"),
                        rs.getString("frenchName"),
                        rs.getString("recipient_field")),
                rule.getId());

        Map<String, List<RecipientItemWithField>> emailByField = emailRecipients.stream()
                .collect(Collectors.groupingBy(e -> e.recipientField().toUpperCase()));

        // Load all available roles
        List<RoleItem> allRoles = jdbc.query(
                ROLES_SQL,
                (rs, rowNum) -> new RoleItem(rs.getLong("id"), rs.getString("frenchName")));

        return RoutingRuleDetail.builder()
                .ruleId(rule.getId())
                .sendInapp(rule.getSendInapp())
                .sendEmail(rule.getSendEmail())
                .inappTitleTemplate(rule.getInappTitleTemplate())
                .inappBodyTemplate(rule.getInappBodyTemplate())
                .emailSubjectTemplate(rule.getEmailSubjectTemplate())
                .emailBodyTemplate(rule.getEmailBodyTemplate())
                .inappRecipients(inappRecipients)
                .emailToRecipients(emailByField.getOrDefault("TO", List.of()))
                .emailCcRecipients(emailByField.getOrDefault("CC", List.of()))
                .emailBccRecipients(emailByField.getOrDefault("BCC", List.of()))
                .availableRoles(allRoles)
                .build();
    }

    /**
     * 3. Partially updates a routing rule (PATCH semantics).
     */
    public NotificationRoutingRule updateRoutingRule(Long ruleId,
                                                     UpdateRoutingRuleRequest dto,
                                                     Long updatedBy) {
        NotificationRoutingRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Routing rule not found: " + ruleId));

        String before = toAuditString(rule);

        if (dto.getSendInapp()            != null) rule.setSendInapp(dto.getSendInapp());
        if (dto.getSendEmail()            != null) rule.setSendEmail(dto.getSendEmail());
        if (dto.getInappTitleTemplate()   != null) rule.setInappTitleTemplate(dto.getInappTitleTemplate());
        if (dto.getInappBodyTemplate()    != null) rule.setInappBodyTemplate(dto.getInappBodyTemplate());
        if (dto.getEmailSubjectTemplate() != null) rule.setEmailSubjectTemplate(dto.getEmailSubjectTemplate());
        if (dto.getEmailBodyTemplate()    != null) rule.setEmailBodyTemplate(dto.getEmailBodyTemplate());

        rule.setUpdatedBy(updatedBy);
        rule.setUpdatedAt(OffsetDateTime.now());

        NotificationRoutingRule saved = ruleRepo.save(rule);

        auditService.log(
                updatedBy != null ? updatedBy.toString() : "SYSTEM",
                "UPDATE_NOTIFICATION_RULE",
                "NOTIFICATION_RULE",
                ruleId,
                before,
                toAuditString(saved));

        return saved;
    }

    /**
     * 4. Adds an in-app recipient role to a routing rule.
     */
    public RecipientItem addInappRecipient(Long ruleId, Long roleId, Long createdBy) {
        NotificationRoutingRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Routing rule not found: " + ruleId));

        // Duplicate check
        boolean exists = inappRecipRepo.findByRuleIdAndIsActiveTrue(ruleId)
                .stream().anyMatch(r -> roleId.equals(r.getRoleId()));
        if (exists) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Role " + roleId + " is already an in-app recipient for rule " + ruleId);
        }

        NotificationRoutingRecipient entity = NotificationRoutingRecipient.builder()
                .rule(rule)
                .roleId(roleId)
                .isActive(true)
                .createdBy(createdBy)
                .build();

        NotificationRoutingRecipient saved = inappRecipRepo.save(entity);

        String roleName = resolveRoleName(roleId);

        auditService.log(
                createdBy != null ? createdBy.toString() : "SYSTEM",
                "ADD_INAPP_RECIPIENT",
                "NOTIFICATION_RULE",
                ruleId,
                null,
                "roleId=" + roleId + " recipientId=" + saved.getId());

        return new RecipientItem(saved.getId(), roleId, roleName);
    }

    /**
     * 5. Hard-deletes an in-app recipient.
     */
    public void removeInappRecipient(Long recipientId, Long deletedBy) {
        inappRecipRepo.findById(recipientId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "In-app recipient not found: " + recipientId));

        inappRecipRepo.deleteById(recipientId);

        auditService.log(
                deletedBy != null ? deletedBy.toString() : "SYSTEM",
                "REMOVE_INAPP_RECIPIENT",
                "NOTIFICATION_RULE",
                null,
                "recipientId=" + recipientId,
                null);
    }

    /**
     * 6. Adds an email recipient role (TO / CC / BCC) to a routing rule.
     */
    public RecipientItem addEmailRecipient(Long ruleId, Long roleId, String field, Long createdBy) {
        String normalizedField = field != null ? field.toUpperCase() : "";
        if (!Set.of("TO", "CC", "BCC").contains(normalizedField)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "recipient_field must be TO, CC, or BCC — got: " + field);
        }

        NotificationRoutingRule rule = ruleRepo.findById(ruleId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Routing rule not found: " + ruleId));

        // Duplicate check per (ruleId, roleId, field)
        boolean exists = emailRecipRepo.findByRuleIdAndIsActiveTrue(ruleId)
                .stream().anyMatch(r ->
                        roleId.equals(r.getRoleId()) &&
                        normalizedField.equalsIgnoreCase(r.getRecipientField()));
        if (exists) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Role " + roleId + " is already a " + normalizedField +
                    " email recipient for rule " + ruleId);
        }

        EmailRoutingRecipient entity = EmailRoutingRecipient.builder()
                .rule(rule)
                .roleId(roleId)
                .recipientField(normalizedField)
                .isActive(true)
                .createdBy(createdBy)
                .build();

        EmailRoutingRecipient saved = emailRecipRepo.save(entity);

        String roleName = resolveRoleName(roleId);

        auditService.log(
                createdBy != null ? createdBy.toString() : "SYSTEM",
                "ADD_EMAIL_RECIPIENT",
                "NOTIFICATION_RULE",
                ruleId,
                null,
                "roleId=" + roleId + " field=" + normalizedField + " recipientId=" + saved.getId());

        return new RecipientItem(saved.getId(), roleId, roleName);
    }

    /**
     * 7. Hard-deletes an email recipient.
     */
    public void removeEmailRecipient(Long recipientId, Long deletedBy) {
        emailRecipRepo.findById(recipientId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Email recipient not found: " + recipientId));

        emailRecipRepo.deleteById(recipientId);

        auditService.log(
                deletedBy != null ? deletedBy.toString() : "SYSTEM",
                "REMOVE_EMAIL_RECIPIENT",
                "NOTIFICATION_RULE",
                null,
                "recipientId=" + recipientId,
                null);
    }

    /**
     * 8. Simulates dispatch for a given event type and pays — no actual
     *    notifications or emails sent.
     */
    @Transactional(readOnly = true)
    public TestDispatchResult testDispatch(Long eventTypeId, Long paysId) {
        Optional<NotificationRoutingRule> ruleOpt =
                ruleRepo.findByEventTypeIdAndIsActiveTrue(eventTypeId);

        if (ruleOpt.isEmpty()) {
            log.warn("testDispatch: no active rule found for eventTypeId={}", eventTypeId);
            return TestDispatchResult.empty();
        }

        NotificationRoutingRule rule = ruleOpt.get();

        // Test template vars
        Map<String, String> vars = Map.of(
                "date",          LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                "candidateName", "Prénom Nom (test)",
                "firstName",     "Prénom",
                "lastName",      "Nom (test)",
                "ms365Email",    "prenom.nom@example.com",
                "entity",        "Test Entité"
        );

        String resolvedTitle   = resolveTemplate(rule.getInappTitleTemplate(), vars);
        String resolvedBody    = resolveTemplate(rule.getInappBodyTemplate(), vars);
        String resolvedSubject = resolveTemplate(rule.getEmailSubjectTemplate(), vars);
        String resolvedHtml    = resolveTemplate(rule.getEmailBodyTemplate(), vars);

        // In-app recipients — look up actual users by role + pays
        List<NotificationRoutingRecipient> inappRoleRecips =
                inappRecipRepo.findByRuleIdAndIsActiveTrue(rule.getId());

        List<TestUserEntry> inappUsers = new ArrayList<>();
        for (NotificationRoutingRecipient rr : inappRoleRecips) {
            List<TestUserEntry> users = jdbc.query(
                    USERS_BY_ROLE_SQL,
                    (rs, rowNum) -> new TestUserEntry(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("email")),
                    rr.getRoleId(), paysId);
            inappUsers.addAll(users);
        }

        // Email recipients — collect as TestEmailEntry(email, roleName) per field
        List<EmailRoutingRecipient> emailRoleRecips =
                emailRecipRepo.findByRuleIdAndIsActiveTrue(rule.getId());

        List<TestEmailEntry> toEmails  = new ArrayList<>();
        List<TestEmailEntry> ccEmails  = new ArrayList<>();
        List<TestEmailEntry> bccEmails = new ArrayList<>();

        for (EmailRoutingRecipient er : emailRoleRecips) {
            String roleName = resolveRoleName(er.getRoleId());
            List<String> emails = jdbc.queryForList(
                    "SELECT COALESCE(username, email) FROM [dbo].[Users] " +
                    "WHERE role_id=? AND pays_id=? AND (isActive=1 OR isActive IS NULL)",
                    String.class, er.getRoleId(), paysId);

            List<TestEmailEntry> entries = emails.stream()
                    .filter(e -> e != null && !e.isBlank())
                    .distinct()
                    .map(e -> new TestEmailEntry(e, roleName))
                    .collect(Collectors.toList());

            switch (er.getRecipientField().toUpperCase()) {
                case "TO"  -> toEmails.addAll(entries);
                case "CC"  -> ccEmails.addAll(entries);
                case "BCC" -> bccEmails.addAll(entries);
            }
        }

        return TestDispatchResult.builder()
                .resolvedTitle(resolvedTitle)
                .resolvedBody(resolvedBody)
                .resolvedSubject(resolvedSubject)
                .resolvedEmailBody(resolvedHtml)
                .inappRecipients(inappUsers)
                .emailTo(toEmails)
                .emailCc(ccEmails)
                .emailBcc(bccEmails)
                .build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private int countById(String sql, Long id) {
        Integer count = jdbc.queryForObject(sql, Integer.class, id);
        return count != null ? count : 0;
    }

    private String resolveRoleName(Long roleId) {
        try {
            return jdbc.queryForObject(ROLE_NAME_SQL, String.class, roleId);
        } catch (Exception e) {
            log.warn("Could not resolve role name for roleId={}", roleId);
            return null;
        }
    }

    private String resolveTemplate(String template, Map<String, String> vars) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private String toAuditString(NotificationRoutingRule rule) {
        return "sendInapp=" + rule.getSendInapp() +
               " sendEmail=" + rule.getSendEmail() +
               " inappTitle=" + rule.getInappTitleTemplate() +
               " inappBody=" + rule.getInappBodyTemplate() +
               " emailSubject=" + rule.getEmailSubjectTemplate();
    }

    // ── Inner DTOs / Records ──────────────────────────────────────────────────

    // recipientField named to match Angular model
    public record RecipientItem(Long id, Long roleId, String roleName) {}
    public record RecipientItemWithField(Long id, Long roleId, String roleName, String recipientField) {}
    public record RoleItem(Long id, String frenchName) {}
    public record TestUserEntry(Long userId, String fullName, String email) {}
    public record TestEmailEntry(String email, String roleName) {}

    // ── Response types ────────────────────────────────────────────────────────

    @lombok.Builder
    @lombok.Getter
    public static class NotificationEventTypeResponse {
        private Long    id;
        private String  eventCode;
        private String  labelFr;
        private String  labelEn;
        private String  module;
        private Boolean supportsEmail;
        private Boolean isSystem;
        private Long    ruleId;
        private Boolean sendInapp;
        private Boolean sendEmail;
        private int     inappRecipientCount;
        private int     emailToCount;
    }

    @lombok.Builder
    @lombok.Getter
    public static class RoutingRuleDetail {
        private Long   ruleId;          // matches Angular RoutingRuleDetail.ruleId
        private Boolean sendInapp;
        private Boolean sendEmail;
        private String  inappTitleTemplate;
        private String  inappBodyTemplate;
        private String  emailSubjectTemplate;
        private String  emailBodyTemplate;
        private List<RecipientItem>          inappRecipients;
        private List<RecipientItemWithField> emailToRecipients;
        private List<RecipientItemWithField> emailCcRecipients;
        private List<RecipientItemWithField> emailBccRecipients;
        private List<RoleItem>               availableRoles;
    }

    @lombok.Builder
    @lombok.Getter
    public static class TestDispatchResult {
        private String  resolvedTitle;        // matches Angular TestDispatchResult
        private String  resolvedBody;
        private String  resolvedSubject;
        private String  resolvedEmailBody;
        private List<TestUserEntry>  inappRecipients;
        private List<TestEmailEntry> emailTo;
        private List<TestEmailEntry> emailCc;
        private List<TestEmailEntry> emailBcc;

        public static TestDispatchResult empty() {
            return TestDispatchResult.builder()
                    .inappRecipients(List.of())
                    .emailTo(List.of())
                    .emailCc(List.of())
                    .emailBcc(List.of())
                    .build();
        }
    }
}
