package com.daf360.rh.service;

import java.util.List;

public interface MailService {
    void sendWelcomeEmail(
        String toEmail,
        String firstName,
        String ms365Email,
        String portalUrl
    );

    /**
     * Sends a routed multi-recipient email with TO, CC, BCC support.
     * Called by NotificationRoutingService. HTML body supported.
     * If toAddresses is empty the implementation must not throw.
     */
    void sendRoutedEmail(
        List<String> toAddresses,
        List<String> ccAddresses,
        List<String> bccAddresses,
        String subject,
        String htmlBody
    );
}
