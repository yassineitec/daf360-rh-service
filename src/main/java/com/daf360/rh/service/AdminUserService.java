package com.daf360.rh.service;

import com.daf360.rh.common.UserScope;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The account register behind Administration → Utilisateurs.
 *
 * Answers the question no existing screen could: WHICH accounts exist, which of them have an HR
 * file, which are test or service accounts, and which have never been signed into. The employee
 * list could not answer it because it is filtered to real people on purpose — the very rows an
 * administrator needs to inspect here are the ones it hides.
 *
 * Deliberately NOT filtered by {@link UserScope}: this is the one screen where a test account
 * must be visible, because it is where an administrator classifies it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /**
     * LEFT JOIN on employee_profiles, never INNER: a user without an HR file is exactly what
     * this screen is for. `has_profile` is the answer, not a filter.
     */
    private static final String LIST_SQL = """
        SELECT u.id, u.fullName, u.username, u.email, u.employee_id,
               u.isActive, u.is_employee, u.last_login_at, u.azure_oid,
               u.pays_id, p.french_label AS pays_label,
               u.role_id, r.frenchName AS role_label,
               ep.id AS profile_id, ep.lifecycle_status
        FROM [dbo].[Users] u
        LEFT JOIN [dbo].[pays] p  ON p.id = u.pays_id
        LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id
        LEFT JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id AND ep.deleted = 0
        """;

    private static final String INSERT_SQL =
        "INSERT INTO [dbo].[Users] " +
        "(fullName, username, email, azure_upn, pays_id, role_id, isActive, is_employee, created_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, 1, ?, SYSDATETIMEOFFSET())";

    private final JdbcTemplate jdbc;

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * @param onlyMissingProfile true to list only accounts with no HR file — the working view
     *                           for "who still needs a profile" and for spotting ghosts.
     */
    @Transactional(readOnly = true)
    public List<AdminUserRow> list(String search, Boolean isEmployee,
                                   Long paysId, Boolean onlyMissingProfile) {
        StringBuilder sql = new StringBuilder(LIST_SQL).append(" WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();

        if (search != null && !search.isBlank()) {
            sql.append("AND (u.fullName LIKE ? OR u.username LIKE ? OR u.email LIKE ?) ");
            String like = "%" + search.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (isEmployee != null) {
            sql.append("AND u.is_employee = ? ");
            args.add(isEmployee ? 1 : 0);
        }
        if (paysId != null) {
            sql.append("AND u.pays_id = ? ");
            args.add(paysId);
        }
        if (Boolean.TRUE.equals(onlyMissingProfile)) {
            sql.append("AND ep.id IS NULL ");
        }
        // Missing profiles first, then never-signed-in: the screen exists to surface exactly
        // those two, so they should not need sorting by hand.
        sql.append("ORDER BY CASE WHEN ep.id IS NULL THEN 0 ELSE 1 END, ")
           .append("CASE WHEN u.last_login_at IS NULL THEN 0 ELSE 1 END, u.fullName");

        return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    /** Counters for the header, so an admin sees the shape of the register at a glance. */
    @Transactional(readOnly = true)
    public AdminUserStats stats() {
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN u.is_employee = 1 THEN 1 ELSE 0 END) AS employees,
                   SUM(CASE WHEN u.is_employee = 0 THEN 1 ELSE 0 END) AS not_employees,
                   SUM(CASE WHEN ep.id IS NULL THEN 1 ELSE 0 END)               AS missing_profile,
                   SUM(CASE WHEN u.last_login_at IS NULL THEN 1 ELSE 0 END)     AS never_logged_in
            FROM [dbo].[Users] u
            LEFT JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id AND ep.deleted = 0
            """, (rs, rn) -> {
            AdminUserStats s = new AdminUserStats();
            s.setTotal(rs.getInt("total"));
            s.setEmployees(rs.getInt("employees"));
            s.setNotEmployees(rs.getInt("not_employees"));
            s.setMissingProfile(rs.getInt("missing_profile"));
            s.setNeverLoggedIn(rs.getInt("never_logged_in"));
            return s;
        });
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Creates an account without going through recruitment.
     *
     * IMPORTANT, and surfaced in the UI: this creates the DAF360 row only. Sign-in is Azure AD,
     * and the match is made on the UPN, so the person cannot log in until an Azure account with
     * the same address exists. Creating a row here does not create an identity.
     *
     * No HR profile is created either — the account appears in this screen as
     * « fiche RH manquante », which is the honest state and the reason the column exists.
     */
    @Transactional
    public AdminUserRow create(CreateUserRequest req, Long actorId) {
        require(req.getFullName(), "fullName");
        require(req.getEmail(), "email");
        if (req.getPaysId() == null) badRequest("paysId is required");
        if (req.getRoleId() == null) badRequest("roleId is required");

        String email = req.getEmail().trim();
        // Defaults to a real person: an admin creating an account is almost always onboarding
        // someone, and the exception (a test or machine account) is the deliberate choice.
        boolean isEmployee = req.getIsEmployee() == null || req.getIsEmployee();

        // username defaults to the email: that is what every existing row does, and it is what
        // the login flow and the notification e-mail resolution both read.
        String username = req.getUsername() != null && !req.getUsername().isBlank()
            ? req.getUsername().trim() : email;

        Integer clash = jdbc.queryForObject(
            "SELECT COUNT(*) FROM [dbo].[Users] WHERE username = ? OR email = ?",
            Integer.class, username, email);
        if (clash != null && clash > 0) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Un compte existe déjà avec cet e-mail ou cet identifiant.");
        }

        jdbc.update(INSERT_SQL, req.getFullName().trim(), username, email, email,
            req.getPaysId(), req.getRoleId(), isEmployee ? 1 : 0);

        Long newId = jdbc.queryForObject(
            "SELECT id FROM [dbo].[Users] WHERE username = ?", Long.class, username);

        log.info("Admin {} created user id={} ({}), isEmployee={} — no Azure identity, no HR profile",
            actorId, newId, email, isEmployee);

        return jdbc.queryForObject(LIST_SQL + " WHERE u.id = ?", this::mapRow, newId);
    }

    /**
     * Flags an account as a person or not — the widest-reaching write in the application.
     *
     * Setting it to false removes the account from every picker and every notification
     * recipient list at once. It does NOT deactivate the account: a machine account keeps
     * signing in and keeps being mirrored to the other services, it simply stops being offered
     * as a human.
     */
    @Transactional
    public void setIsEmployee(Long userId, Boolean isEmployee, Long actorId) {
        if (isEmployee == null) badRequest("isEmployee is required");
        int updated = jdbc.update(
            "UPDATE [dbo].[Users] SET is_employee = ? WHERE id = ?", isEmployee ? 1 : 0, userId);
        if (updated == 0) {
            throw new AppException(ErrorCode.NOT_FOUND, "Utilisateur introuvable: " + userId);
        }
        log.info("Admin {} set is_employee={} on user {}", actorId, isEmployee, userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void require(String value, String field) {
        if (value == null || value.isBlank()) badRequest(field + " is required");
    }

    private void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private AdminUserRow mapRow(ResultSet rs, int rn) throws SQLException {
        AdminUserRow r = new AdminUserRow();
        r.setId(rs.getLong("id"));
        r.setFullName(rs.getString("fullName"));
        r.setUsername(rs.getString("username"));
        r.setEmail(rs.getString("email"));
        r.setEmployeeId(rs.getString("employee_id"));
        r.setActive(rs.getObject("isActive") == null || rs.getBoolean("isActive"));
        r.setEmployee(rs.getBoolean("is_employee"));
        r.setLastLoginAt(rs.getObject("last_login_at", OffsetDateTime.class));
        // Not "has logged in": the oid is written when the account is matched to Azure. It
        // answers "could this person sign in at all", which is the useful distinction here.
        r.setHasAzureIdentity(rs.getString("azure_oid") != null);
        r.setPaysId(rs.getObject("pays_id", Long.class));
        r.setPaysLabel(rs.getString("pays_label"));
        r.setRoleId(rs.getObject("role_id", Long.class));
        r.setRoleLabel(rs.getString("role_label"));
        long profileId = rs.getLong("profile_id");
        r.setProfileId(rs.wasNull() ? null : profileId);
        r.setHasProfile(r.getProfileId() != null);
        r.setLifecycleStatus(rs.getString("lifecycle_status"));
        return r;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Data
    public static class AdminUserRow {
        private Long id;
        private String fullName;
        private String username;
        private String email;
        private String employeeId;
        private boolean active;
        /** False for a test, duplicate or machine account — hidden from every list of people. */
        private boolean employee;
        private OffsetDateTime lastLoginAt;
        private boolean hasAzureIdentity;
        private Long paysId;
        private String paysLabel;
        private Long roleId;
        private String roleLabel;
        /** Null when the account has no HR file — the point of this screen. */
        private Long profileId;
        private boolean hasProfile;
        private String lifecycleStatus;
    }

    @Data
    public static class AdminUserStats {
        private int total;
        private int employees;
        private int notEmployees;
        private int missingProfile;
        private int neverLoggedIn;
    }

    @Data
    public static class CreateUserRequest {
        private String fullName;
        private String email;
        /** Defaults to the e-mail when omitted. */
        private String username;
        private Long paysId;
        private Long roleId;
        /** Defaults to true when omitted. */
        private Boolean isEmployee;
    }
}
