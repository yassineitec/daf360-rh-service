package com.daf360.rh.security;

import com.daf360.rh.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Validates JWTs issued by the DAF360 Portal.
 *
 * Supports both algorithms the portal may use:
 *   - RS256 (RSA) when JWT_PUBLIC_KEY_PATH is configured — used by production portal
 *   - HS256 (HMAC) via JWT_SECRET as legacy fallback
 *
 * Algorithm is detected from the JWT header so no explicit configuration is needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties appProperties;

    @PostConstruct
    public void logConfiguration() {
        String secret = appProperties.getJwtSecret();
        String pubKeyPath = appProperties.getJwtPublicKeyPath();
        // Print first 8 chars so we can verify both services use the same value
        // without exposing the full secret in logs.
        String prefix = (secret != null && secret.length() >= 8)
                ? secret.substring(0, 8) : secret;
        log.info("╔══ JWT CONFIG ══════════════════════════════════════════════");
        log.info("║  HMAC secret  : '{}...' (len={})", prefix, secret != null ? secret.length() : 0);
        log.info("║  RSA key path : '{}'", pubKeyPath != null ? pubKeyPath : "(not set — RSA disabled)");
        log.info("╚════════════════════════════════════════════════════════════");
    }

    public Claims parseToken(String token) {
        if (isRs256(token)) {
            PublicKey pub = loadPublicKey();
            if (pub != null) {
                return Jwts.parser().verifyWith(pub).build()
                        .parseSignedClaims(token).getPayload();
            }
            log.warn("RS256 token received but JWT_PUBLIC_KEY_PATH not configured — falling back to HS256");
        }
        return Jwts.parser()
                .verifyWith(getHmacKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isRs256(String token) {
        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
            return headerJson.contains("RS256");
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey getHmacKey() {
        byte[] keyBytes = appProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private PublicKey loadPublicKey() {
        String path = appProperties.getJwtPublicKeyPath();
        if (path == null || path.isBlank()) return null;
        try {
            String pem = Files.readString(Paths.get(path))
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            log.error("Failed to load RSA public key from '{}': {}", path, e.getMessage());
            return null;
        }
    }
}
