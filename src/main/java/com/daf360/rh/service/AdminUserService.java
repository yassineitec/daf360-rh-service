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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
               u.soldeConge, u.soldeMaladie, u.soldeTeletravail,
               -- photo_url + gender feed the avatar; hire_date is the one extra fact this
               -- screen can show without a second join. `department` and `grade` are NOT here:
               -- they live on RSS_employee_profiles, a different table, and pulling them in
               -- would mean a join whose semantics this screen has never needed.
               ep.id AS profile_id, ep.lifecycle_status,
               ep.photo_url, ep.gender, ep.hire_date
        FROM [dbo].[Users] u
        LEFT JOIN [dbo].[pays] p  ON p.id = u.pays_id
        LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id
        LEFT JOIN [dbo].[employee_profiles] ep ON ep.user_id = u.id AND ep.deleted = 0
        """;

    /**
     * The three leave allowances, and the only columns this service will write on them.
     *
     * An allow-list rather than a field name taken from the request: the value goes into an
     * UPDATE, and the set of balances is fixed by the schema. A fourth allowance would be a
     * deliberate edit here, not something a caller can reach by naming a new column.
     */
    private static final Set<String> BALANCE_COLUMNS =
            Set.of("soldeConge", "soldeMaladie", "soldeTeletravail");

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
        r.setPhotoUrl(rs.getString("photo_url"));
        r.setGender(rs.getString("gender"));
        r.setHireDate(rs.getObject("hire_date", LocalDate.class));
        // Read as objects: these are nullable floats and 135 of 260 users have none recorded.
        // getDouble() would turn every one of those into 0.0 — telling half the company they
        // have no leave left, which is a different statement from "not set".
        r.setSoldeConge(rs.getObject("soldeConge", Double.class));
        r.setSoldeMaladie(rs.getObject("soldeMaladie", Double.class));
        r.setSoldeTeletravail(rs.getObject("soldeTeletravail", Double.class));
        return r;
    }

    // ── Balances ──────────────────────────────────────────────────────────────

    /**
     * Set an employee's leave allowances.
     *
     * NULL IS A REAL VALUE HERE and is preserved on the way in as well as out: clearing a
     * balance back to "not recorded" is a different act from setting it to zero, and only the
     * second one says "you have none left".
     *
     * Each balance is written only when the caller sent it, so a screen that edits one does
     * not silently blank the other two by omitting them.
     *
     * Logged with the previous value: this grants or removes leave, and "who changed it from
     * what" is the first question anyone asks afterwards.
     */
    @Transactional
    public AdminUserRow updateBalances(Long userId, Map<String, Double> balances, Long actorId) {
        AdminUserRow before = byId(userId);
        if (before == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "Utilisateur introuvable : " + userId);
        }

        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Double> e : balances.entrySet()) {
            if (!BALANCE_COLUMNS.contains(e.getKey())) {
                throw new AppException(ErrorCode.INVALID_TRANSITION, "Solde inconnu : " + e.getKey());
            }
            if (e.getValue() != null && e.getValue() < 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un solde ne peut pas être négatif : " + e.getKey());
            }
            // Bracketed, never interpolated from the request: the key has already been matched
            // against BALANCE_COLUMNS, so only those three literals can reach the statement.
            //
            // NULL IS WRITTEN AS A LITERAL, NOT AS A PARAMETER. Spring hands an untyped null
            // to `setNull(i, Types.NULL)`, which the SQL Server driver rejects — so clearing a
            // balance back to "not recorded" failed with a 500 while setting one worked. The
            // literal is safe here for the same reason the column name is: neither comes from
            // the request, only from this method.
            if (e.getValue() == null) {
                sets.add("[" + e.getKey() + "] = NULL");
            } else {
                sets.add("[" + e.getKey() + "] = ?");
                args.add(e.getValue());
            }
        }
        if (sets.isEmpty()) {
            return before;
        }

        args.add(userId);
        jdbc.update("UPDATE [dbo].[Users] SET " + String.join(", ", sets) + " WHERE id = ?",
                args.toArray());

        log.info("Balances of user {} changed by {}: congé {} -> {}, maladie {} -> {}, télétravail {} -> {}",
                userId, actorId,
                before.getSoldeConge(), balances.getOrDefault("soldeConge", before.getSoldeConge()),
                before.getSoldeMaladie(), balances.getOrDefault("soldeMaladie", before.getSoldeMaladie()),
                before.getSoldeTeletravail(), balances.getOrDefault("soldeTeletravail", before.getSoldeTeletravail()));

        return byId(userId);
    }

    /** One row by id, for the before/after around a balance change. */
    @Transactional(readOnly = true)
    public AdminUserRow byId(Long userId) {
        List<AdminUserRow> rows = jdbc.query(LIST_SQL + " WHERE u.id = ?", this::mapRow, userId);
        return rows.isEmpty() ? null : rows.get(0);
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
        /** Feeds the avatar, exactly as the congé screens do — see LeaveRequestMapper.Face. */
        private String photoUrl;
        private String gender;
        private LocalDate hireDate;
        /**
         * Leave allowances, in days. NULLABLE and meaningfully so: 135 of 260 users have no
         * congé balance recorded, and "not set" is not "none remaining".
         */
        private Double soldeConge;
        private Double soldeMaladie;
        private Double soldeTeletravail;
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
