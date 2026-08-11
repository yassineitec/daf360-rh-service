package com.daf360.rh.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Graph API mail implementation.
 * Active when mail.enabled=true AND mail.provider=graph.
 * Obtains an OAuth2 client-credentials token and caches it until near-expiry.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mail.enabled",  havingValue = "true")
@ConditionalOnProperty(name = "mail.provider", havingValue = "graph")
public class GraphMailServiceImpl implements MailService {

    private static final String TOKEN_URL   = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final String SEND_URL    = "https://graph.microsoft.com/v1.0/users/%s/sendMail";
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    @Value("${microsoft.graph.tenant-id}")
    private String tenantId;

    @Value("${microsoft.graph.client-id}")
    private String clientId;

    @Value("${microsoft.graph.client-secret}")
    private String clientSecret;

    @Value("${mail.from:HR.DAF@arx.ing}")
    private String from;

    private final RestClient restClient = RestClient.create();

    private volatile String cachedToken;
    private volatile long   tokenExpiresAt = 0L;

    // ── MailService ──────────────────────────────────────────────

    @Override
    public void sendWelcomeEmail(String toEmail, String firstName,
                                  String ms365Email, String portalUrl) {
        String html = buildWelcomeHtml(firstName, ms365Email, portalUrl);
        sendRoutedEmail(
                List.of(toEmail), List.of(), List.of(),
                "Bienvenue chez ARX — Activez votre compte DAF360",
                html);
    }

    @Override
    public void sendRoutedEmail(List<String> toAddresses, List<String> ccAddresses,
                                 List<String> bccAddresses, String subject, String htmlBody) {
        if (toAddresses == null || toAddresses.isEmpty()) {
            log.warn("[GRAPH] sendRoutedEmail called with empty TO list — skipped");
            return;
        }
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("subject", subject != null ? subject : "Notification DAF360");
            message.put("body", Map.of("contentType", "HTML",
                    "content", htmlBody != null ? htmlBody : ""));
            message.put("toRecipients", recipientList(toAddresses));
            if (ccAddresses  != null && !ccAddresses.isEmpty())
                message.put("ccRecipients",  recipientList(ccAddresses));
            if (bccAddresses != null && !bccAddresses.isEmpty())
                message.put("bccRecipients", recipientList(bccAddresses));

            Map<String, Object> payload = Map.of("message", message, "saveToSentItems", false);

            restClient.post()
                    .uri(SEND_URL.formatted(from))
                    .header("Authorization", "Bearer " + getToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("[GRAPH] Email sent — to={} subject={}", toAddresses.size(), subject);
        } catch (Exception ex) {
            log.error("[GRAPH] Failed to send email subject='{}': {}", subject, ex.getMessage());
        }
    }

    // ── Token management ─────────────────────────────────────────

    private synchronized String getToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "client_credentials");
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("scope",         GRAPH_SCOPE);

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restClient.post()
                .uri(TOKEN_URL.formatted(tenantId))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        cachedToken    = (String)  resp.get("access_token");
        int expiresIn  = (Integer) resp.get("expires_in");
        tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
        log.debug("[GRAPH] New OAuth2 token obtained, expires in {}s", expiresIn);
        return cachedToken;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private List<Map<String, Object>> recipientList(List<String> addresses) {
        return addresses.stream()
                .map(a -> Map.<String, Object>of("emailAddress", Map.of("address", a)))
                .toList();
    }

    private String buildWelcomeHtml(String firstName, String ms365Email, String portalUrl) {
        return """
                <html><body style="font-family:Arial,sans-serif;color:#333">
                <p>Bonjour %s,</p>
                <p>Nous sommes ravis de vous accueillir au sein d'ARX.<br>
                Votre dossier a été complété avec succès.</p>
                <p>Pour accéder à votre espace collaborateur DAF360,
                cliquez sur le lien ci-dessous et connectez-vous avec
                votre adresse Microsoft 365 :</p>
                <p><a href="%s" style="color:#0078d4">%s</a></p>
                <p><strong>Identifiant :</strong> %s<br>
                <strong>Authentification :</strong> Microsoft 365<br>
                (aucun mot de passe séparé requis)</p>
                <p>Bienvenue dans l'équipe !<br>
                <em>L'équipe RH ARX</em></p>
                </body></html>
                """.formatted(firstName, portalUrl, portalUrl, ms365Email);
    }
}
