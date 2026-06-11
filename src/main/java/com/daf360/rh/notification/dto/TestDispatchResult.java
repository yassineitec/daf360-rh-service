package com.daf360.rh.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TestDispatchResult {

    private List<UserPreview> inappRecipients;
    private List<EmailPreview> emailTo;
    private List<EmailPreview> emailCc;
    private List<EmailPreview> emailBcc;

    private String resolvedTitle;
    private String resolvedBody;
    private String resolvedSubject;
    private String resolvedEmailBody;

    @Data
    public static class UserPreview {
        private Long userId;
        private String fullName;
        private String email;
    }

    @Data
    public static class EmailPreview {
        private String email;
        private String roleName;
    }
}
