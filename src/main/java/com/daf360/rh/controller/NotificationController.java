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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the notification panel in the shell header.
 *
 * All endpoints filter strictly by the caller's own user_id (from JWT sub) so users can
 * never see each other's notifications. Deliberately NOT permission-gated: a notification
 * is addressed to one person, and gating the read on a permission would mean someone could
 * be sent an alert they are then forbidden to open.
 *
 * Table: [dbo].[notifications]
 *   id, user_id, module, title, message, is_read (BIT), created_at, read_at,
 *   entity_type, entity_id, link
 *
 * entity_type + entity_id are the deep link: the frontend maps a kind to a route, so a
 * route rename never invalidates stored rows. link is the escape hatch for targets no
 * entity kind describes. All three are nullable — a notification that points nowhere is
 * normal, and must render as non-clickable rather than as a dead link.
 */
@RestController
@RequestMapping("/api/hr/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /** Hard ceiling on one page, whatever the caller asks for. */
    private static final int MAX_LIMIT     = 200;
    private static final int DEFAULT_LIMIT = 50;

    private static final String SELECT_COLUMNS =
        "id, user_id, module, title, message, is_read, created_at, read_at, " +
        "entity_type, entity_id, link";

    private final JdbcTemplate jdbc;

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Most recent notifications for the current user, newest first.
     *
     * @param module optional filter, for the panel's module chips (RH, POINTAGE, ...).
     * @param limit  optional page size, capped at {@link #MAX_LIMIT}.
     */
    @GetMapping
    public List<NotifDto> list(Authentication auth,
                               @RequestParam(required = false) String module,
                               @RequestParam(required = false) Integer limit) {
        Long userId = actorId(auth);
        if (userId == null) return List.of();

        int size = clampLimit(limit);
        List<Object> args = new ArrayList<>();
        args.add(userId);

        StringBuilder sql = new StringBuilder(
            "SELECT " + SELECT_COLUMNS + " FROM [dbo].[notifications] WHERE user_id = ? ");
        if (module != null && !module.isBlank()) {
            sql.append("AND module = ? ");
            args.add(module.trim().toUpperCase());
        }
        // ORDER BY + OFFSET is served by IX_Notif_User_Created (user_id, created_at DESC).
        // `size` is an int clamped above, never caller text — no injection surface.
        sql.append("ORDER BY created_at DESC OFFSET 0 ROWS FETCH NEXT ")
           .append(size)
           .append(" ROWS ONLY");

        return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    /**
     * Unread totals for the current user: one overall count for the bell badge plus a
     * per-module breakdown for the panel's filter chips, in a single round trip — the
     * shell polls this endpoint on a timer, so a second query per poll is a real cost.
     */
    @GetMapping("/unread-count")
    public UnreadCountDto unreadCount(Authentication auth) {
        UnreadCountDto dto = new UnreadCountDto();
        Long userId = actorId(auth);
        if (userId == null) return dto;

        // Served by IX_Notif_User_Unread (user_id, is_read).
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT module, COUNT(*) AS c FROM [dbo].[notifications] " +
            "WHERE user_id = ? AND is_read = 0 GROUP BY module",
            userId
        );

        int total = 0;
        Map<String, Integer> byModule = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String moduleCode = (String) row.get("module");
            int count = ((Number) row.get("c")).intValue();
            if (moduleCode != null) byModule.put(moduleCode, count);
            total += count;
        }
        dto.setCount(total);
        dto.setByModule(byModule);
        return dto;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Marks a single notification as read. Only the owning user can do this. */
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, Authentication auth) {
        Long userId = actorId(auth);
        if (userId == null) return ResponseEntity.status(401).build();
        jdbc.update(
            "UPDATE [dbo].[notifications] SET is_read = 1, read_at = SYSDATETIMEOFFSET() " +
            "WHERE id = ? AND user_id = ? AND is_read = 0",
            id, userId
        );
        return ResponseEntity.ok().build();
    }

    /**
     * Marks unread notifications as read for the current user.
     *
     * @param module optional — when the panel is filtered to one module, "mark all read"
     *               must clear that module only, not the ones the user is not looking at.
     */
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(Authentication auth,
                                            @RequestParam(required = false) String module) {
        Long userId = actorId(auth);
        if (userId == null) return ResponseEntity.status(401).build();

        if (module != null && !module.isBlank()) {
            jdbc.update(
                "UPDATE [dbo].[notifications] SET is_read = 1, read_at = SYSDATETIMEOFFSET() " +
                "WHERE user_id = ? AND is_read = 0 AND module = ?",
                userId, module.trim().toUpperCase()
            );
        } else {
            jdbc.update(
                "UPDATE [dbo].[notifications] SET is_read = 1, read_at = SYSDATETIMEOFFSET() " +
                "WHERE user_id = ? AND is_read = 0",
                userId
            );
        }
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
        // read_at is a real nullable column since V11; the old try/catch around it hid
        // any genuine mapping failure behind a silent null.
        dto.setReadAt(rs.getObject("read_at", OffsetDateTime.class));
        dto.setEntityType(rs.getString("entity_type"));
        long entityId = rs.getLong("entity_id");
        dto.setEntityId(rs.wasNull() ? null : entityId);
        dto.setLink(rs.getString("link"));
        return dto;
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

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
        /** Deep link — all three null when the notification points nowhere. */
        private String entityType;
        private Long entityId;
        private String link;
    }

    @Data
    public static class UnreadCountDto {
        private int count;
        private Map<String, Integer> byModule = new LinkedHashMap<>();
    }
}
