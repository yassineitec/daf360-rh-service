package com.daf360.rh.service;

import com.daf360.rh.dto.leave.LeaveApproverDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Who may approve this employee's leave.
 *
 * THERE IS NO MANAGER COLUMN
 * -----------------------------------------------------------------------------
 * Not in DAF360_HR and not in the timesheet — portal {@code Users.manager_id} exists only
 * as a commented-out field. The approver set is DERIVED from the role hierarchy, walking
 * {@code Roles.parent_role_id} upward from the employee's own role, and collecting the
 * holders of each ancestor role within the same country. That is what the timesheet's
 * {@code UserServiceImpl.getManagers} does, and it is reproduced here as a single recursive
 * CTE rather than a loop issuing one query per level.
 *
 * The role trees were compared across both databases on 2026-09-28: 21 roles each, and every
 * parent relationship matches by name except one — {@code Administrateur} is a child of
 * {@code Responsable planner} in the timesheet and a root in DAF360_HR. Being a root is the
 * more sensible shape, so the walk simply stops earlier for those users here. No other role
 * resolves differently.
 *
 * THREE RULES BEYOND THE PLAIN WALK, all carried across:
 *
 *   1. ROOT ROLES IGNORE COUNTRY. Holders of a role with no parent are offered as approvers
 *      regardless of their pays. A company with one PDG must not leave the employees of
 *      every other country with no one above them.
 *
 *   2. showAll MAKES PEERS APPROVERS. When the employee's OWN role carries showAll, everyone
 *      holding that same role in that country joins the set — a flat team where members
 *      cover for each other.
 *
 *   3. EGYPT IS SPECIAL, and questionably so. See {@link #EGYPT_ISO}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveApproverService {

    /**
     * The timesheet adds, for Egyptian employees only, every user whose role has NO
     * subordinate roles in that country — {@code SIZE(u.role.subordinateRoles) = 0}.
     *
     * That is the definition of a LEAF role, which is the opposite of a manager. It reads
     * like a workaround for an org chart that did not fit the tree rather than an intended
     * rule. It is ported unchanged so the move changes nothing an Egyptian employee sees,
     * and flagged here because it is the first thing to revisit if Egyptian approver lists
     * look wrong.
     */
    private static final String EGYPT_ISO = "EG";

    private final JdbcTemplate jdbcTemplate;

    /**
     * The approver list the request form offers.
     *
     * Excludes the employee themselves. The timesheet intended to as well, but filtered with
     * {@code m.getEmail() != user.getEmail()} — reference comparison on String, which is
     * almost never true for two separately-loaded rows, so employees could and did appear in
     * their own approver list. Compared by id here.
     */
    @Transactional(readOnly = true)
    public List<LeaveApproverDto> approversFor(Long userId) {
        String sql = """
                WITH ancestors AS (
                    -- The employee's own role, seeded so that rule 2 (showAll) can see it.
                    SELECT r.id, r.parent_role_id, r.showAll, 0 AS depth
                    FROM Roles r
                    WHERE r.id = (SELECT cu.role_id FROM Users cu WHERE cu.id = ?)
                      AND (r.deleted = 0 OR r.deleted IS NULL)
                    UNION ALL
                    -- …then every role above it.
                    SELECT p.id, p.parent_role_id, p.showAll, a.depth + 1
                    FROM Roles p
                    JOIN ancestors a ON a.parent_role_id = p.id
                    WHERE (p.deleted = 0 OR p.deleted IS NULL)
                )
                SELECT DISTINCT u.id, u.fullName, COALESCE(u.email, u.username) AS email,
                       r.frenchName AS role_name
                FROM Users u
                JOIN ancestors a ON a.id = u.role_id
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                  AND u.id <> ?
                  AND (
                        -- rule 1: a root role's holders, wherever they are
                        a.parent_role_id IS NULL
                        -- an ancestor proper, same country
                     OR (a.depth > 0 AND u.pays_id = (SELECT c2.pays_id FROM Users c2 WHERE c2.id = ?))
                        -- rule 2: peers, but only when the employee's own role says showAll
                     OR (a.depth = 0 AND a.showAll = 1
                         AND u.pays_id = (SELECT c3.pays_id FROM Users c3 WHERE c3.id = ?))
                  )
                ORDER BY u.fullName
                """;

        List<LeaveApproverDto> walked = jdbcTemplate.query(
                sql,
                (rs, n) -> new LeaveApproverDto(
                        rs.getLong("id"),
                        rs.getString("fullName"),
                        rs.getString("email"),
                        rs.getString("role_name")),
                userId, userId, userId, userId);

        List<LeaveApproverDto> egypt = egyptFallback(userId);
        if (egypt.isEmpty()) {
            return walked;
        }
        // Union by id, preserving the walked order first.
        java.util.LinkedHashMap<Long, LeaveApproverDto> merged = new java.util.LinkedHashMap<>();
        walked.forEach(a -> merged.put(a.userId(), a));
        egypt.forEach(a -> merged.putIfAbsent(a.userId(), a));
        return List.copyOf(merged.values());
    }

    /** Rule 3 — see {@link #EGYPT_ISO}. Returns empty for every other country. */
    private List<LeaveApproverDto> egyptFallback(Long userId) {
        String sql = """
                SELECT u.id, u.fullName, COALESCE(u.email, u.username) AS email,
                       r.frenchName AS role_name
                FROM Users u
                LEFT JOIN Roles r ON r.id = u.role_id
                WHERE u.isActive = 1
                  AND u.id <> ?
                  AND u.pays_id = (SELECT c.pays_id FROM Users c WHERE c.id = ?)
                  AND EXISTS (SELECT 1 FROM pays p
                              WHERE p.id = u.pays_id AND UPPER(p.iso_code) = ?)
                  AND NOT EXISTS (SELECT 1 FROM Roles child
                                  WHERE child.parent_role_id = u.role_id
                                    AND (child.deleted = 0 OR child.deleted IS NULL))
                ORDER BY u.fullName
                """;
        try {
            return jdbcTemplate.query(
                    sql,
                    (rs, n) -> new LeaveApproverDto(
                            rs.getLong("id"),
                            rs.getString("fullName"),
                            rs.getString("email"),
                            rs.getString("role_name")),
                    userId, userId, EGYPT_ISO);
        } catch (Exception e) {
            // Never let the country-specific rule break the form for everyone else.
            log.warn("Egypt approver fallback failed for user {} — continuing with the role walk only: {}",
                    userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * The approvers for ONE leave type, when that type names its own approver roles.
     *
     * A type may restrict who signs it off — sick leave to HR, ordinary leave to the line
     * manager. `RoleApprovableAbsenceTypes` holds that mapping, and this resolves it for a
     * given employee: walk upward from the employee's role, and at each ancestor whose role
     * is configured as an approver for this type, take the holders in the employee's country.
     *
     * Two properties of that walk are deliberate and both come from the original:
     *
     *   STARTS AT THE PARENT, not the employee's own role. A colleague holding the same role
     *   is a peer, not an approver, even if that role appears in the type's list.
     *
     *   ONLY ANCESTORS COUNT. A role may be configured as an approver for the type and still
     *   produce nobody, because nobody above THIS employee holds it. That empty result is
     *   meaningful — see LeaveTypeOptionDto — and must not be quietly replaced with the
     *   generic manager list.
     *
     * Returns empty when the type configures no approver roles at all; the caller treats that
     * case as "use the default hierarchy list" and the two are distinguished by the count.
     */
    @Transactional(readOnly = true)
    public List<LeaveApproverDto> eligibleApproversFor(String typeCode, Long userId) {
        String sql = """
                WITH ancestors AS (
                    -- Start at the PARENT: a peer in the same role is not an approver.
                    SELECT r.id, r.parent_role_id
                    FROM Roles r
                    WHERE r.id = (SELECT p.parent_role_id
                                  FROM Roles p
                                  WHERE p.id = (SELECT cu.role_id FROM Users cu WHERE cu.id = ?))
                      AND (r.deleted = 0 OR r.deleted IS NULL)
                    UNION ALL
                    SELECT p.id, p.parent_role_id
                    FROM Roles p
                    JOIN ancestors a ON a.parent_role_id = p.id
                    WHERE (p.deleted = 0 OR p.deleted IS NULL)
                )
                SELECT DISTINCT u.id, u.fullName, COALESCE(u.email, u.username) AS email,
                       r.frenchName AS role_name
                FROM Users u
                JOIN ancestors a ON a.id = u.role_id
                LEFT JOIN Roles r ON r.id = u.role_id
                JOIN RoleApprovableAbsenceTypes rat ON rat.role_id = u.role_id
                JOIN AbsenceTypes t ON t.id = rat.absence_type_id
                WHERE u.isActive = 1
                  AND u.id <> ?
                  AND t.code = ?
                  AND (t.deleted = 0 OR t.deleted IS NULL)
                  AND u.pays_id = (SELECT c.pays_id FROM Users c WHERE c.id = ?)
                ORDER BY u.fullName
                """;
        return jdbcTemplate.query(
                sql,
                (rs, n) -> new LeaveApproverDto(
                        rs.getLong("id"),
                        rs.getString("fullName"),
                        rs.getString("email"),
                        rs.getString("role_name")),
                userId, userId, typeCode, userId);
    }

    /** How many roles a type names as approvers. Zero means "no restriction configured". */
    @Transactional(readOnly = true)
    public int approverRoleCount(String typeCode) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM RoleApprovableAbsenceTypes rat
                JOIN AbsenceTypes t ON t.id = rat.absence_type_id
                WHERE t.code = ? AND (t.deleted = 0 OR t.deleted IS NULL)
                """, Integer.class, typeCode);
        return n == null ? 0 : n;
    }

    /**
     * Whether this user may decide on that request — the server-side counterpart of the
     * approver dropdown, checked again at decision time because the dropdown is a
     * convenience and not a control.
     */
    @Transactional(readOnly = true)
    public boolean canDecide(Long userId, Long responsableId, Long responsableAdjointId) {
        return userId != null
                && (userId.equals(responsableId) || userId.equals(responsableAdjointId));
    }

    /**
     * Everyone reporting into this user, for the team-history screen: the mirror of the walk
     * above, downward. Same shape as MissionService.eligibleEmployees, which answers the
     * equivalent question for missions.
     */
    @Transactional(readOnly = true)
    public List<Long> subordinateUserIds(Long managerId) {
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
                SELECT u.id
                FROM Users u
                WHERE u.isActive = 1
                  AND u.role_id IN (SELECT id FROM descendants)
                  AND u.id <> ?
                  AND u.pays_id = (SELECT c2.pays_id FROM Users c2 WHERE c2.id = ?)
                """;
        return jdbcTemplate.queryForList(sql, Long.class, managerId, managerId, managerId);
    }
}
