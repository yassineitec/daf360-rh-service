package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around MailService for fixed-recipient alerts (HR manager, Finance).
 * All sends are @Async — never blocks the request thread.
 * Silently swallows send failures (logs only) so a mail outage never breaks a business operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final MailService    mailService;
    private final AppProperties  props;

    @Async
    public void sendToHrManager(String subject, String body) {
        send(props.getHrManagerEmail(), subject, body);
    }

    @Async
    public void sendToFinance(String subject, String body) {
        send(props.getFinanceEmail(), subject, body);
    }

    @Async
    public void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            log.debug("Notification skipped — no recipient configured for: {}", subject);
            return;
        }
        mailService.sendRoutedEmail(
                java.util.List.of(to),
                java.util.List.of(),
                java.util.List.of(),
                "[DAF360 RH] " + subject,
                "<pre>" + body + "</pre>");
    }
}
