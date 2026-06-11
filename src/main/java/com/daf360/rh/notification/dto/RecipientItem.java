package com.daf360.rh.notification.dto;

import lombok.Data;

@Data
public class RecipientItem {

    private Long id;
    private Long roleId;
    private String roleName;

    /** Nullable — present only for email recipients (e.g. a dynamic field expression). */
    private String recipientField;
}
