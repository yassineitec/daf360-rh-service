package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.dto.profile.EmployeeAvatarDto;
import com.daf360.rh.service.EmployeeProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Avatar references by e-mail, for the timesheet application.
 *
 * Same answer as {@code GET /api/hr/profiles/avatars} (which facturation uses), keyed by
 * e-mail instead of user id: the timesheet's user ids are not DAF360_HR's, the two are matched
 * on e-mail (TIMESHEET-INTEGRATION-DECISIONS.md, C0.5). The timesheet backend calls this once
 * per page of time entries to draw each manager's photo; the browser then loads the bytes from
 * the public {@code /api/hr/profiles/{profileId}/photo}.
 *
 * Internal (X-Internal-Key) like the working calendar: the caller is a server that has already
 * authorised its own user, and only a profile id, a photo-presence flag and a version come back.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalAvatarController {

    private static final String HEADER = "X-Internal-Key";
    /** One page of entries carries a handful of managers; this only bounds a runaway list. */
    private static final int MAX_EMAILS = 100;

    private final EmployeeProfileService profileService;
    private final JdbcTemplate jdbcTemplate;
    private final AppProperties appProperties;

    /** {@link EmployeeAvatarDto} plus the e-mail it was asked for (normalised: trimmed, lower case). */
    public record EmailAvatar(String email, Long profileId, String photoUrl, Long photoVersion, String fullName) {}

    /**
     * GET /api/hr/internal/avatars?emails=a@x.com,b@y.com
     * Unknown e-mails are simply absent; the caller falls back to initials.
     */
    @GetMapping("/api/hr/internal/avatars")
    public ResponseEntity<List<EmailAvatar>> avatarsByEmail(
            @RequestParam List<String> emails,
            @RequestHeader(value = HEADER, required = false) String key) {

        requireInternalKey(key);
        List<String> wanted = emails.stream()
                .filter(Objects::nonNull)
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .distinct()
                .limit(MAX_EMAILS)
                .toList();
        if (wanted.isEmpty()) return ResponseEntity.ok(List.of());

        String placeholders = String.join(",", Collections.nCopies(wanted.size(), "?"));
        Map<Long, String> emailByUserId = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, LOWER(LTRIM(RTRIM(email))) AS email FROM [dbo].[Users] "
                        + "WHERE LOWER(LTRIM(RTRIM(email))) IN (" + placeholders + ")",
                rs -> { emailByUserId.put(rs.getLong("id"), rs.getString("email")); },
                wanted.toArray());
        if (emailByUserId.isEmpty()) return ResponseEntity.ok(List.of());

        List<EmailAvatar> out = profileService.resolveAvatars(List.copyOf(emailByUserId.keySet())).stream()
                .map(a -> new EmailAvatar(emailByUserId.get(a.userId()), a.profileId(), a.photoUrl(),
                        a.photoVersion(), a.fullName()))
                .toList();
        return ResponseEntity.ok(out);
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        byte[] x = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = provided == null ? new byte[0] : provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(x, y)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }
}
