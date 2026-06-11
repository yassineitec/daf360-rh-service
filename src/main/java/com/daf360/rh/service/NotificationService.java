package com.daf360.rh.service;

import com.daf360.rh.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around JavaMailSender.
 * All sends are @Async — never blocks the request thread.
 * Silently swallows send failures (logs only) so a mail outage never breaks a business operation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender  mailSender;
    private final AppProperties   props;

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
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to);
            msg.setSubject("[DAF360 RH] " + subject);
            msg.setText(body);
            mailSender.send(msg);
            log.debug("Notification sent to={} subject={}", to, subject);
        } catch (Exception ex) {
            log.warn("Failed to send notification to={} subject={}: {}", to, subject, ex.getMessage());
        }
    }
}
