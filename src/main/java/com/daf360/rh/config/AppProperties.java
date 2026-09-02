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

    /**
     * Consuming modules to notify when an account changes — see ModuleSyncService.
     *
     * This reverses the usual direction: normally the consumers pull from RH and RH knows
     * nothing about them. The reversal is deliberate and its scope is deliberately tiny —
     * RH holds a URL and nothing else, and every consumer keeps its own 15-minute pull as
     * the correctness floor. What this buys is the one case the pull cannot serve: an
     * account deactivated in an emergency, where 15 minutes is not an acceptable delay.
     *
     * Blank disables that consumer, which is how a deployment without payroll (or a local
     * run with the container stopped) avoids a pointless failed call on every propagation.
     */
    private String factApiBaseUrl    = "";
    private String payrollApiBaseUrl = "";

    // ── Microsoft Graph / SharePoint (document storage) ────────────────────────
    // Blank tenant/client id/secret = integration disabled: GraphSharePointService
    // skips the upload silently (local disk save, which happens first and unconditionally,
    // is unaffected either way). Never hardcode real values here — env vars only.
    private String msGraphTenantId         = "";
    private String msGraphClientId         = "";
    private String msGraphClientSecret     = "";
    private String sharepointSiteHostname  = "pinigroup.sharepoint.com";
    private String sharepointSitePath      = "/sites/pini-tunisia";

    public String getPdfServiceUrl() { return pdfServiceUrl; }
}
