package com.daf360.rh.notification.dto;

import lombok.Data;

@Data
public class NotificationEventTypeResponse {

    private Long id;
    private String eventCode;
    private String labelFr;
    private String labelEn;
    private String module;
    private Boolean supportsEmail;
    private Boolean isSystem;

    /** Nullable — null when no routing rule has been created yet for this event type. */
    private Long ruleId;

    /** Nullable — null when no routing rule exists. */
    private Boolean sendInapp;

    /** Nullable — null when no routing rule exists. */
    private Boolean sendEmail;

    private int inappRecipientCount;
    private int emailToCount;
}
