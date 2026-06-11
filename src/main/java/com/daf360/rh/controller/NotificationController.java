package com.daf360.rh.controller;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Serves the notification panel in the HR frontend.
 *
 * All endpoints filter strictly by the caller's own user_id (from JWT sub)
 * so users can never see each other's notifications.
 *
 * Table: [dbo].[notifications]
 *   id, user_id, module, title, message, is_read (BIT), created_at, read_at
 */
@RestController
@RequestMapping("/api/hr/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final JdbcTemplate jdbc;

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the 50 most recent notifications for the current user. */
    @GetMapping
    public List<NotifDto> list(Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) return List.of();
        return jdbc.query(
            "SELECT id, user_id, module, title, message, is_read, created_at, read_at " +
            "FROM [dbo].[notifications] " +
            "WHERE user_id = ? " +
            "ORDER BY created_at DESC " +
            "OFFSET 0 ROWS FETCH NEXT 50 ROWS ONLY",
            this::mapRow, userId
        );
    }

    /** Returns the count of unread notifications for the current user. */
    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount(Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) return Map.of("count", 0);
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[notifications] WHERE user_id = ? AND is_read = 0",
            Integer.class, userId
        );
        return Map.of("count", count != null ? count : 0);
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Marks a single notification as read. Only the owning user can do this. */
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        jdbc.update(
            "UPDATE [dbo].[notifications] SET is_read = 1, read_at = SYSDATETIMEOFFSET() " +
            "WHERE id = ? AND user_id = ?",
            id, userId
        );
        return ResponseEntity.ok().build();
    }

    /** Marks all unread notifications as read for the current user. */
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        jdbc.update(
            "UPDATE [dbo].[notifications] SET is_read = 1, read_at = SYSDATETIMEOFFSET() " +
            "WHERE user_id = ? AND is_read = 0",
            userId
        );
        return ResponseEntity.ok().build();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private NotifDto mapRow(ResultSet rs, int rn) throws SQLException {
        NotifDto dto = new NotifDto();
        dto.setId(rs.getLong("id"));
        dto.setUserId(rs.getLong("user_id"));
        dto.setModule(rs.getString("module"));
        dto.setTitle(rs.getString("title"));
        dto.setMessage(rs.getString("message"));
        dto.setIsRead(rs.getBoolean("is_read"));
        dto.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        try {
            dto.setReadAt(rs.getObject("read_at", OffsetDateTime.class));
        } catch (Exception e) {
            dto.setReadAt(null);
        }
        return dto;
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    @Data
    public static class NotifDto {
        private Long id;
        private Long userId;
        private String module;
        private String title;
        private String message;
        /** Serialised as "isRead" to match the Angular HrNotification interface. */
        private Boolean isRead;
        private OffsetDateTime createdAt;
        private OffsetDateTime readAt;
    }
}
