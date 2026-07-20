package com.daf360.rh.controller;

import com.daf360.rh.dto.UserForSyncDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal service-to-service endpoint consumed by the facturation-service
 * UserRefSyncService every 15 minutes to keep users_ref in sync.
 * Permitted without authentication (internal network only — see SecurityConfig).
 */
@Slf4j
@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class UserSyncController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/users-for-sync")
    public List<UserForSyncDto> usersForSync() {
        log.debug("UserSyncController: serving users-for-sync");
        String sql = """
                SELECT u.id, u.azure_oid, u.fullName, u.email, u.pays_id,
                       r.frenchName AS role_name, u.isActive
                FROM Users u
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                ORDER BY u.id
                """;
        return jdbcTemplate.query(sql, (rs, rn) -> new UserForSyncDto(
                rs.getLong("id"),
                rs.getString("azure_oid"),
                rs.getString("fullName"),
                rs.getString("email"),
                rs.getLong("pays_id"),
                rs.getString("role_name"),
                rs.getBoolean("isActive")
        ));
    }

    /**
     * The managers of a given user: active users whose role is the parent of the user's role
     * (Roles.parent_role_id). Empty when the user's role is top-level. Requires a valid token
     * (called by the pointage service on the caller's behalf, forwarding their JWT).
     */
    @GetMapping("/users/{userId}/managers")
    public List<UserForSyncDto> managersForUser(@PathVariable Long userId) {
        String sql = """
                SELECT u.id, u.azure_oid, u.fullName, u.email, u.pays_id,
                       r.frenchName AS role_name, u.isActive
                FROM Users u
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                  AND u.role_id = (
                      SELECT pr.id
                      FROM Users cu
                      JOIN Roles cr  ON cr.id = cu.role_id
                      JOIN Roles pr  ON pr.id = cr.parent_role_id
                      WHERE cu.id = ?
                  )
                ORDER BY u.fullName
                """;
        return jdbcTemplate.query(sql, (rs, rn) -> new UserForSyncDto(
                rs.getLong("id"),
                rs.getString("azure_oid"),
                rs.getString("fullName"),
                rs.getString("email"),
                rs.getLong("pays_id"),
                rs.getString("role_name"),
                rs.getBoolean("isActive")
        ), userId);
    }
}
