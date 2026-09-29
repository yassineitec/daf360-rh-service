package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.ApproverResolutionStrategy;
import com.daf360.rh.domain.enums.Gender;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps [dbo].[AbsenceTypes] in DAF360_HR — one configurable kind of leave.
 *
 * WHY THIS IS A TABLE AND NOT AN ENUM
 * -----------------------------------------------------------------------------
 * It used to be an enum. The timesheet deleted {@code TypeAbsence} and replaced it with this
 * table, and {@code Absence.type} became a plain String holding {@link #code}. HR administers
 * the catalogue through a screen: adding a type, retiring one, changing what it costs and who
 * may approve it are configuration, not deployments.
 *
 * The first version of this port reproduced the old 13-value enum, because it was written
 * against a branch 110 commits behind. That is what this replaces.
 *
 * WHAT IS ACTUALLY ENFORCED
 * -----------------------------------------------------------------------------
 * Verified against master (2026-08-18) rather than assumed from the field names — two of
 * these columns are edited in the admin screen and read by nothing:
 *
 *   ENFORCED   tracksBalance + balanceField  → whether and which balance is debited
 *              maxDays                       → rejected above this many days
 *              requiresJustification         → a request without one is rejected
 *              includedInHrStats             → filters the HR statistics query
 *              managerCanView                → filters what a manager's queue shows
 *
 *   INERT      allowedGender                 → stored, never checked
 *              approverResolutionStrategy    → stored, never read; see the enum
 *
 * Both inert fields are mapped anyway so the admin screen round-trips them. Being explicit
 * about which is which is the point: a reader who assumes `allowedGender` gates maternity
 * leave would be wrong, and would only find out from a user.
 */
@Entity
/*
 * The backticks are load-bearing.
 *
 * Spring Boot's CamelCaseToUnderscoresNamingStrategy rewrites EXPLICIT @Table and @Column
 * names, not just the implicit ones derived from the field name — so a bare
 * `@Table(name = "AbsenceTypes")` reaches SQL Server as `absence_types`, which does not
 * exist. Quoting the identifier opts out of the rewrite.
 *
 * Single-word names elsewhere in this package (`Roles`, `Users`) survive unquoted only
 * because there is no camel hump to split on and SQL Server's collation is
 * case-insensitive. Any multi-word name needs this treatment. See RolePermission, where
 * the same omission silently created a second, near-empty `role_permissions` table.
 */
@Table(name = "`AbsenceTypes`")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AbsenceType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The stable key. This — not the id — is what {@code absences.type} stores, so renaming a
     * code orphans every request already filed under it.
     */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelFr;

    @Column(name = "label_en", nullable = false, length = 255, columnDefinition = "nvarchar(255)")
    private String labelEn;

    /**
     * Mapped to `isActive`, NOT to `active`.
     *
     * The table carries both columns — the same duplicate-spelling artefact as `absences` —
     * and the timesheet entity maps `isActive`. Mapping the other one would read a column
     * nothing writes (every row has `active` NULL and `isActive` set).
     *
     * BACKTICKS ARE LOAD-BEARING. Spring Boot's default
     * CamelCaseToUnderscoresNamingStrategy rewrites EXPLICIT @Column names as well as
     * derived ones, so plain "isActive" reaches the database as `is_active` — a column that
     * does not exist, and every read of this entity fails with an opaque 500. Backticks mark
     * it as an already-quoted identifier and it is passed through untouched. rh's own Role
     * entity quotes `frenchName` and `showAll` for exactly this reason.
     *
     * Every other column on this table is snake_case, so this is the only one affected.
     */
    @Column(name = "`isActive`", nullable = false)
    private Boolean active;

    /** Whether approving a request of this type debits an allowance at all. */
    @Column(name = "tracks_balance", nullable = false)
    private Boolean tracksBalance;

    /**
     * Which allowance, as a code: CONGE or MALADIE, naming a `Users.solde*` column.
     *
     * A string rather than an enum because the catalogue is data: a new type pointing at a
     * new balance is a row, and the only thing stopping that today is that `Users` has just
     * the three solde columns.
     */
    @Column(name = "balance_field", length = 32)
    private String balanceField;

    /** Whether this type appears in the HR statistics report. */
    @Column(name = "included_in_hr_stats", nullable = false)
    private Boolean includedInHrStats;

    /** A request of this type is rejected unless the employee promises a document. */
    @Column(name = "requires_justification", nullable = false)
    private Boolean requiresJustification;

    /** Hard cap on a single request, in days. Null means no cap. */
    @Column(name = "max_days")
    private Integer maxDays;

    /** Order in the type dropdown. The catalogue is ordered by this, not alphabetically. */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** INERT — see the class comment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "allowed_gender", length = 16)
    private Gender allowedGender;

    /**
     * Whether a manager sees requests of this type for their team.
     *
     * False hides the row from the team and country queues — the mechanism by which a
     * medically sensitive absence stays between the employee and HR.
     */
    @Column(name = "manager_can_view", nullable = false)
    private Boolean managerCanView;

    /** INERT — see {@link ApproverResolutionStrategy}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "approver_resolution_strategy", length = 32)
    private ApproverResolutionStrategy approverResolutionStrategy;

    /** Soft delete: a retired type must still resolve for the requests already filed under it. */
    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    void onCreate() {
        if (deleted == null) deleted = Boolean.FALSE;
        if (active == null) active = Boolean.TRUE;
        if (tracksBalance == null) tracksBalance = Boolean.FALSE;
        if (includedInHrStats == null) includedInHrStats = Boolean.FALSE;
        if (requiresJustification == null) requiresJustification = Boolean.FALSE;
        if (managerCanView == null) managerCanView = Boolean.TRUE;
        if (displayOrder == null) displayOrder = 0;
    }

    /** Label in the caller's language; the HR Excel exports render server-side. */
    public String getLabel(String lang) {
        return "fr".equalsIgnoreCase(lang) ? labelFr : labelEn;
    }

    /** True when approving a request of this type moves a balance. */
    public boolean drawsOnBalance() {
        return Boolean.TRUE.equals(tracksBalance) && balanceField != null && !balanceField.isBlank();
    }

    /** True when {@code days} exceeds this type's cap. A null cap never exceeds. */
    public boolean exceedsMaxDays(BigDecimal days) {
        return maxDays != null && days != null && days.doubleValue() > maxDays;
    }
}
