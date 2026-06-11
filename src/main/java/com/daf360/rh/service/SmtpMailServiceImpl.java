package com.daf360.rh.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Real SMTP implementation — active only when mail.enabled=true.
 * Reads MAIL_FROM env var for the sender address.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mail.enabled", havingValue = "true")
public class SmtpMailServiceImpl implements MailService {

    private final JavaMailSender mailSender;

    @Value("${mail.from:noreply@daf360.com}")
    private String from;

    private static final String SUBJECT = "Bienvenue chez ARX — Activez votre compte DAF360";

    @Override
    public void sendWelcomeEmail(String toEmail, String firstName,
                                  String ms365Email, String portalUrl) {
        String body = buildBody(firstName, ms365Email, portalUrl);
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(toEmail);
        msg.setSubject(SUBJECT);
        msg.setText(body);
        try {
            mailSender.send(msg);
            log.info("Welcome email sent to={}", toEmail);
        } catch (Exception ex) {
            log.error("Failed to send welcome email to={}: {}", toEmail, ex.getMessage());
        }
    }

    @Override
    public void sendRoutedEmail(List<String> toAddresses, List<String> ccAddresses,
                                 List<String> bccAddresses, String subject, String htmlBody) {
        if (toAddresses == null || toAddresses.isEmpty()) {
            log.warn("sendRoutedEmail called with empty TO list — skipped");
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            for (String to  : toAddresses)  helper.addTo(to);
            for (String cc  : ccAddresses)  helper.addCc(cc);
            for (String bcc : bccAddresses) helper.addBcc(bcc);
            helper.setSubject(subject != null ? subject : "Notification DAF360");
            helper.setText(htmlBody != null ? htmlBody : "", true);
            mailSender.send(message);
            log.info("[SMTP] Routed email sent — TO={} CC={} subject={}", toAddresses.size(), ccAddresses.size(), subject);
        } catch (Exception ex) {
            log.error("[SMTP] Failed to send routed email: {}", ex.getMessage());
        }
    }

    private String buildBody(String firstName, String ms365Email, String portalUrl) {
        return String.format("""
                Bonjour %s,

                Nous sommes ravis de vous accueillir au sein d'ARX.
                Votre dossier a été complété avec succès.

                Pour accéder à votre espace collaborateur DAF360,
                cliquez sur le lien ci-dessous et connectez-vous avec
                votre adresse Microsoft 365 :

                %s

                Identifiant       : %s
                Authentification  : Microsoft 365
                (aucun mot de passe séparé requis)

                Bienvenue dans l'équipe !
                L'équipe RH ARX
                """,
                firstName, portalUrl, ms365Email);
    }
}
