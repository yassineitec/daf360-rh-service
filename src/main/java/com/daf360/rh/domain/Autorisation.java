package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.DemandeEtat;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[autorisations] — a short paid absence, measured in MINUTES.
 *
 * WHY IT IS NOT A {@link LeaveRequest} WITH A DIFFERENT TYPE
 * -----------------------------------------------------------------------------
 * Two hours out for a doctor is not a short congé. It draws on no balance and counts no
 * days: the control is a monthly minute quota, configured per entity. A congé spans whole or
 * half days and debits a solde. The two share a workflow — submit, approve, régularise — and
 * nothing else, so they share screens' shape rather than a table.
 *
 * MINUTES, UNDER A NAME THAT SAYS SO
 * -----------------------------------------------------------------------------
 * The timesheet called this `nombreHeures` and stored minutes in it — its caps compare the
 * field against 120 and 240 directly. The name is corrected here (V114 carries the data); a
 * column that lies about its unit is re-learned by every reader and eventually believed.
 *
 * NO SHARED BASE CLASS
 * -----------------------------------------------------------------------------
 * The timesheet had `Demande` as a @MappedSuperclass over Absence, Autorisation and
 * Teletravail. {@link LeaveRequest} deliberately did not reproduce it, and neither does this:
 * the base had no table of its own so its columns were copied into all three anyway, and
 * introducing it now would mean either refactoring LeaveRequest to inherit it — churn on
 * working code — or a model where one sibling inherits and the other does not.
 */
@Entity
@Table(
    name = "autorisations",
    indexes = {
        // The quota checks read exactly the first one: everything this employee holds on a
        // day or in a month. Without it every create table-scans.
        @Index(name = "IX_autorisations_collaborateur", columnList = "collaborateur_id, date_autorisation"),
        @Index(name = "IX_autorisations_responsable",   columnList = "responsable_id, etat_demande"),
        @Index(name = "IX_autorisations_etat_date",     columnList = "etat_demande, date_autorisation")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Autorisation {

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

    /**
     * Frozen at creation rather than joined — an employee can transfer, and a country's quota
     * report should show what was taken in that country.
     */
    @Column(name = "pays_id")
    private Long paysId;

    // ── When ─────────────────────────────────────────────────────────────────

    /** One day. An absence spanning two days is a congé, and the form will not offer it. */
    @Column(name = "date_autorisation", nullable = false)
    private LocalDate dateAutorisation;

    @Column(name = "start_time", nullable = false, columnDefinition = "TIME(0)")
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, columnDefinition = "TIME(0)")
    private LocalTime endTime;

    /**
     * Duration in MINUTES, computed server-side from start and end.
     *
     * Never taken from the client — the same rule the congé day-count follows, and for the
     * same reason: it is what the quota is spent against, so a caller who could send it could
     * choose what their absence costs.
     */
    @Column(name = "duree_minutes", nullable = false)
    private Integer dureeMinutes;

    // ── State ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_demande", nullable = false, length = 16)
    private DemandeEtat etatDemande;

    @Column(name = "reason", columnDefinition = "NVARCHAR(MAX)")
    private String reason;

    @Column(name = "motif_refus", columnDefinition = "NVARCHAR(MAX)")
    private String motifRefus;

    /** Why it was cancelled — the employee's own account, never an approver's. */
    @Column(name = "motif_annulation", columnDefinition = "NVARCHAR(MAX)")
    private String motifAnnulation;

    @Column(name = "date_validation")
    private LocalDate dateValidation;

    @Column(name = "decided_by")
    private Long decidedBy;

    /**
     * Who filed it. Set on EVERY row, not only régularisations, so
     * {@code createdBy <> collaborateurId} is a reliable test rather than one that also
     * matches rows nobody recorded.
     */
    @Column(name = "created_by")
    private Long createdBy;

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

    /** Still awaiting a decision — editable by its owner, countable against no quota yet. */
    public boolean isPending() {
        return etatDemande == DemandeEtat.EN_ATTENTE;
    }
}
