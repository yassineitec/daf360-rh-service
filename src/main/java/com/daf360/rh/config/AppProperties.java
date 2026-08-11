package com.daf360.rh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String jwtSecret;
    private long jwtExpirySeconds = 3600;
    /** Path to the portal's RSA public key .pem — same JWT_PUBLIC_KEY_PATH env var the portal uses. */
    private String jwtPublicKeyPath = "";
    private List<String> allowedOrigins = List.of();
    private String storagePath     = "./uploads/hr";
    private String hrManagerEmail  = "";
    private String financeEmail    = "";
    private String companyName     = "DAF360";
    private String portalUrl       = "http://localhost:8080";
    private String mailFrom        = "noreply@daf360.com";
    private String pdfServiceUrl   = "http://localhost:3000";
    /**
     * Shared secret for service-to-service reads under /api/hr/internal/**. Background
     * jobs in other modules (log-service's presence scheduler) have no user token, so
     * they authenticate with this key via the X-Internal-Key header. Blank disables
     * those endpoints entirely.
     */
    private String internalApiKey  = "";
    /** Shared key for inter-service calls (payroll-service → rh-service). */
    private String serviceKey      = "";

    public String getPdfServiceUrl() { return pdfServiceUrl; }
}
