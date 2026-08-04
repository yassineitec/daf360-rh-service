package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.service.RegimeResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-to-service regime reads for background jobs that have no user token.
 *
 * log-service's presence scheduler runs on a timer, so there is no caller identity to
 * forward — it previously called RH anonymously and every request 401'd, which silently
 * disabled all break and end-of-shift automation. These endpoints are authenticated by
 * a shared secret instead, and are read-only.
 *
 * Guarded in SecurityConfig by path; the key check lives here so a missing/blank key
 * fails closed with 403 rather than exposing the route.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalRegimeController {

    private static final String HEADER = "X-Internal-Key";

    private final RegimeResolutionService resolutionService;
    private final AppProperties appProperties;

    /**
     * GET /api/hr/internal/users/{userId}/regime
     * The resolved regime including its break windows — everything the presence
     * scheduler needs in one call, so it no longer touches /api/hr/breaks/templates
     * (which requires ADMIN_BREAKS).
     */
    @GetMapping("/api/hr/internal/users/{userId}/regime")
    public ResponseEntity<ResolvedRegimeDto> getRegimeForUser(
            @PathVariable Long userId,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        ResolvedRegimeDto resolved = resolutionService.resolveForUser(userId);
        if (resolved == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(resolved);
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        if (provided == null || !constantTimeEquals(expected, provided)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }

    /** Length-independent comparison so the key cannot be probed byte by byte. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
