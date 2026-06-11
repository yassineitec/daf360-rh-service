package com.daf360.rh.notification.dto;

import lombok.Data;

/**
 * PATCH request body for updating a notification routing rule.
 * All fields are nullable — only non-null fields are applied (PATCH semantics).
 */
@Data
public class UpdateRoutingRuleRequest {

    private Boolean sendInapp;
    private Boolean sendEmail;

    private String inappTitleTemplate;
    private String inappBodyTemplate;

    private String emailSubjectTemplate;
    private String emailBodyTemplate;
}
