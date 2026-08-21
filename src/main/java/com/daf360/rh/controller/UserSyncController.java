package com.daf360.rh.controller;

import com.daf360.rh.dto.PaysForSyncDto;
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
                // `getObject(..., Long.class)` et NON `getLong` : sur une colonne NULL,
                // `getLong` renvoie 0. Ce 0 partait tel quel dans le réplica des services
                // consommateurs, où `pays_id` porte une clé étrangère vers `pays_ref` —
                // aucune entité n'a l'id 0, donc l'insertion échouait et, `saveAll` étant
                // transactionnel, un seul utilisateur sans entité bloquait la
                // synchronisation de TOUS les autres.
                rs.getObject("pays_id", Long.class),
                rs.getString("role_name"),
                rs.getBoolean("isActive")
        ));
    }

    /**
     * Entities (pays) for the consuming services' `pays_ref` shadow tables.
     *
     * The payroll service has called this since day one (`ProfileSyncService.syncPays`)
     * but it did not exist: the sync threw on every run, the exception was swallowed by
     * its own `catch`, and `pays_ref` stayed empty — which is why the country dropdown
     * was blank on EVERY payroll screen (simulateur, administration, calibration…).
     *
     * `deleted = 0` only: a soft-deleted entity must not come back as a selectable
     * country in another service.
     */
    @GetMapping("/pays-for-sync")
    public List<PaysForSyncDto> paysForSync() {
        log.debug("UserSyncController: serving pays-for-sync");
        String sql = """
                SELECT id, iso_code, french_label
                FROM [dbo].[pays]
                WHERE deleted = 0
                ORDER BY id
                """;
        return jdbcTemplate.query(sql, (rs, rn) -> new PaysForSyncDto(
                rs.getLong("id"),
                rs.getString("iso_code"),
                rs.getString("french_label"),
                // No currency column on [dbo].[pays] — see PaysForSyncDto.
                null
        ));
    }

    /**
     * The managers of a given user: active users whose role is the parent of the user's role
     * (Roles.parent_role_id). Empty when the user's role is top-level. Requires a valid token
     * (called by the pointage service on the caller's behalf, forwarding their JWT).
     *
     * Restricted to the user's own `pays_id`: the parent role exists in every country, so this
     * used to return the manager role's holders across ALL of them — a Tunisian employee's
     * "managers" included the Egyptian ones. A hierarchy that crosses entities is not one.
     * Users with no pays_id fall back to the unfiltered set rather than to nothing.
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
                  AND (
                      u.pays_id = (SELECT cu2.pays_id FROM Users cu2 WHERE cu2.id = ?)
                      OR (SELECT cu3.pays_id FROM Users cu3 WHERE cu3.id = ?) IS NULL
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
        ), userId, userId, userId);
    }

    /**
     * The exact inverse of {@link #managersForUser}: active users whose role is a DESCENDANT
     * of the given user's role. The manager's team, resolved from the org chart.
     *
     * Recursive, not one level: a DRH manages the RH role's holders and the Assistante RH's
     * under them. Same direction as permission inheritance (Roles.parent_role_id points at
     * the parent, so a manager's reports are the roles pointing down from theirs).
     *
     * The anchor deliberately starts at the CHILDREN of the caller's role, never the role
     * itself — otherwise every holder of the same role would appear as their own peer's
     * subordinate, and a team of equals is not a team.
     *
     * Restricted to the user's own `pays_id` for the same reason as managersForUser: the
     * child roles exist in every country, so without the filter a Tunisian manager's team
     * would include the Egyptian holders. Users with no pays_id fall back to the unfiltered
     * set rather than to nothing.
     *
     * Soft-deleted roles are skipped, which also truncates anything below them — a report
     * whose whole role was deleted is not a report.
     */
    @GetMapping("/users/{userId}/subordinates")
    public List<UserForSyncDto> subordinatesForUser(@PathVariable Long userId) {
        String sql = """
                WITH descendants AS (
                    SELECT r.id
                    FROM Roles r
                    WHERE r.parent_role_id = (SELECT cu.role_id FROM Users cu WHERE cu.id = ?)
                      AND (r.deleted = 0 OR r.deleted IS NULL)
                    UNION ALL
                    SELECT c.id
                    FROM Roles c
                    JOIN descendants d ON c.parent_role_id = d.id
                    WHERE (c.deleted = 0 OR c.deleted IS NULL)
                )
                SELECT u.id, u.azure_oid, u.fullName, u.email, u.pays_id,
                       r.frenchName AS role_name, u.isActive
                FROM Users u
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                  AND u.role_id IN (SELECT id FROM descendants)
                  AND u.id <> ?
                  AND (
                      u.pays_id = (SELECT cu2.pays_id FROM Users cu2 WHERE cu2.id = ?)
                      OR (SELECT cu3.pays_id FROM Users cu3 WHERE cu3.id = ?) IS NULL
                  )
                ORDER BY u.fullName
                """;
        return jdbcTemplate.query(sql, (rs, rn) -> new UserForSyncDto(
                rs.getLong("id"),
                rs.getString("azure_oid"),
                rs.getString("fullName"),
                rs.getString("email"),
                rs.getObject("pays_id", Long.class),
                rs.getString("role_name"),
                rs.getBoolean("isActive")
        ), userId, userId, userId, userId);
    }
}
