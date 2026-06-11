package com.daf360.rh.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default mail implementation — logs the email body instead of sending.
 * Active when mail.enabled is absent or false.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mail.enabled", havingValue = "false", matchIfMissing = true)
public class LogMailServiceImpl implements MailService {

    @Override
    public void sendWelcomeEmail(String toEmail, String firstName,
                                  String ms365Email, String portalUrl) {
        log.info("[MAIL-LOG] Welcome email — to={} subject=\"Bienvenue chez ARX — Activez votre compte DAF360\"", toEmail);
        log.info("[MAIL-LOG] Bonjour {},\n\n" +
                 "Nous sommes ravis de vous accueillir au sein d\'ARX.\n" +
                 "Votre dossier a été complété avec succès.\n\n" +
                 "Portail DAF360 : {}\n" +
                 "Identifiant   : {}\n" +
                 "Authentification : Microsoft 365\n\n" +
                 "Bienvenue dans l\'équipe !\n" +
                 "L\'équipe RH ARX",
                 firstName, portalUrl, ms365Email);
    }

    @Override
    public void sendRoutedEmail(List<String> toAddresses, List<String> ccAddresses,
                                 List<String> bccAddresses, String subject, String htmlBody) {
        log.info("[MAIL-LOG] ROUTED EMAIL subject='{}' TO={} CC={} BCC={}",
            subject, toAddresses, ccAddresses, bccAddresses);
        log.debug("[MAIL-LOG] HTML body: {}", htmlBody);
    }
}
