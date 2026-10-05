package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[teletravails] — one day (or half a day) of remote work.
 *
 * ONE DAY PER ROW, AND A DURATION THE TIMESHEET NEVER HAD
 * -----------------------------------------------------------------------------
 * The timesheet stored a date and nothing else: every request was a whole day and cost one
 * unit of soldeTeletravail. Here a request carries a {@link LeaveCategory} — FULL_DAY,
 * HALF_DAY_MORNING or HALF_DAY_AFTERNOON — the same enum the congés use, so "half a day"
 * means the same thing on both desks. MULTIPLE_DAYS is refused by the service: a week of
 * remote work is five requests, each of which an approver may answer differently.
 *
 * {@code jours} is DERIVED from the category, never taken from the client — it is what the
 * balance and the caps are spent against, the same rule the congé day-count follows.
 *
 * THE DEBIT IS RECORDED ON THE ROW
 * -----------------------------------------------------------------------------
 * Whether a balance is drawn is an entity rule an administrator can switch off, so "refund
 * on archive" cannot be inferred from today's rule: a request approved while the rule was on
 * must still be refunded after it is turned off, and one approved while it was off must not
 * be. {@code balanceDebited} records what actually happened.
 */
@Entity
@Table(
    name = "teletravails",
    indexes = {
        // Every rule reads exactly this: what this employee already holds on a day, a week,
        // a month. Without it every create table-scans.
        @Index(name = "IX_teletravails_collaborateur", columnList = "collaborateur_id, date_teletravail"),
        @Index(name = "IX_teletravails_responsable",   columnList = "responsable_id, etat_demande"),
        @Index(name = "IX_teletravails_etat_date",     columnList = "etat_demande, date_teletravail")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Teletravail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Who ──────────────────────────────────────────────────────────────────

    @Column(name = "collaborateur_id", nullable = false)
    private Long collaborateurId;

    /** Chosen by the employee from the set the role hierarchy allows, as for a congé. */
    @Column(name = "responsable_id")
    private Long responsableId;

    /** Optional second approver; either may decide. */
    @Column(name = "responsable_adjoint_id")
    private Long responsableAdjointId;

    /** Frozen at creation — an employee can transfer, and a country's report should not move. */
    @Column(name = "pays_id")
    private Long paysId;

    // ── When ─────────────────────────────────────────────────────────────────

    @Column(name = "date_teletravail", nullable = false)
    private LocalDate dateTeletravail;

    /** FULL_DAY, HALF_DAY_MORNING or HALF_DAY_AFTERNOON — never MULTIPLE_DAYS. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private LeaveCategory category;

    /** 1.0 or 0.5, derived from the category. DECIMAL: it is subtracted from a balance. */
    @Column(name = "jours", nullable = false, precision = 3, scale = 1)
    private BigDecimal jours;

    // ── State ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_demande", nullable = false, length = 16)
    private DemandeEtat etatDemande;

    @Column(name = "reason", columnDefinition = "NVARCHAR(MAX)")
    private String reason;

    @Column(name = "motif_refus", columnDefinition = "NVARCHAR(MAX)")
    private String motifRefus;

    /** Why it was withdrawn — the employee's own account, never an approver's. */
    @Column(name = "motif_annulation", columnDefinition = "NVARCHAR(MAX)")
    private String motifAnnulation;

    @Column(name = "date_validation")
    private LocalDate dateValidation;

    @Column(name = "decided_by")
    private Long decidedBy;

    /** Who filed it — set on every row, so {@code createdBy <> collaborateurId} marks a régularisation. */
    @Column(name = "created_by")
    private Long createdBy;

    /** True once soldeTeletravail was debited for this row — see the class comment. */
    @Column(name = "balance_debited", nullable = false)
    private boolean balanceDebited;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (etatDemande == null) etatDemande = DemandeEtat.EN_ATTENTE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public boolean isPending() {
        return etatDemande == DemandeEtat.EN_ATTENTE;
    }

    /** The half of the day this request occupies, or both for a full day. */
    public boolean coversMorning() {
        return category != LeaveCategory.HALF_DAY_AFTERNOON;
    }

    public boolean coversAfternoon() {
        return category != LeaveCategory.HALF_DAY_MORNING;
    }
}
