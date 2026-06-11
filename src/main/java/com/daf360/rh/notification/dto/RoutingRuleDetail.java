package com.daf360.rh.notification.dto;

import lombok.Data;

import java.util.List;

@Data
public class RoutingRuleDetail {

    private Long ruleId;
    private NotificationEventTypeResponse eventType;

    private Boolean sendInapp;
    private Boolean sendEmail;

    private String inappTitleTemplate;
    private String inappBodyTemplate;

    /** Nullable — only relevant when sendEmail is true. */
    private String emailSubjectTemplate;

    /** Nullable — only relevant when sendEmail is true. */
    private String emailBodyTemplate;

    private List<RecipientItem> inappRecipients;
    private List<RecipientItem> emailToRecipients;
    private List<RecipientItem> emailCcRecipients;
    private List<RecipientItem> emailBccRecipients;

    /** All roles available for assignment in the UI. */
    private List<RoleOption> availableRoles;
}
