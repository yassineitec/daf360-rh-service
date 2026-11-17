package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.ModificationState;
import com.daf360.rh.domain.enums.ModificationType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[absence_modification_requests] — a proposal standing against an ALREADY
 * APPROVED {@link LeaveRequest}.
 *
 * WHY THIS IS ITS OWN ROW AND NOT AN EDIT
 * -----------------------------------------------------------------------------
 * By the time somebody wants to move or cancel a congé, the approval has already had an
 * effect: the days are debited and the manager has moved on. Editing the absence in place
 * would destroy the approved state the proposal is about, and there would be nowhere to hold
 * dates that are proposed but not yet agreed. So the leave stays exactly as approved while
 * this row carries what is being asked for, and only an approved proposal writes to it.
 *
 * TWO TYPES, TWO PATHS
 * -----------------------------------------------------------------------------
 * MODIFICATION starts at PENDING_MANAGER, ANNULATION at PENDING_HR — see
 * {@link ModificationType#initialState()} for why the manager is skipped for a cancellation.
 * Both end at APPROVED or REJECTED, and only the HR stage touches data.
 *
 * WHAT CHANGES WHEN IT IS APPROVED
 * -----------------------------------------------------------------------------
 * The balance adjustment goes through {@link AbsenceType}'s {@code tracksBalance} /
 * {@code balanceField}, NOT through a type-to-column mapping in code. The timesheet
 * hardcoded {@code CONGE -> soldeConge, MALADIE -> soldeMaladie, else nothing}, so a type
 * drawing on télétravail silently adjusted no balance at all and a new type needed a
 * deployment. Here a type is a row and the column it draws on is one of its fields.
 *
 * COLUMN NAMES ARE FRENCH, STATE NAMES ARE ENGLISH
 * -----------------------------------------------------------------------------
 * The columns match {@code dbo.absences}, which is French because 716 migrated rows already
 * spell it that way; the states are new, so they follow the rest of rh-service. Making
 * either half match the other would leave one of the two inconsistent with its neighbours.
 */
@Entity
@Table(
    name = "absence_modification_requests",
    indexes = {
        @Index(name = "IX_amr_collaborateur",    columnList = "collaborateur_id, created_at"),
        @Index(name = "IX_amr_responsable_etat", columnList = "responsable_id, etat"),
        @Index(name = "IX_amr_etat_pays",        columnList = "etat, pays_id, created_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveModificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── What it stands against ───────────────────────────────────────────────

    /**
     * The approved leave being contested. Not a {@code @ManyToOne}: every screen that shows a
     * proposal shows the leave beside it, and the service already loads leaves by id — a lazy
     * association here would buy nothing and cost a proxy on every list page.
     */
    @Column(name = "absence_id", nullable = false)
    private Long absenceId;

    // ── Who ──────────────────────────────────────────────────────────────────

    /** The employee asking. A {@code Users.id}, like {@link LeaveRequest#getCollaborateurId()}. */
    @Column(name = "collaborateur_id", nullable = false)
    private Long collaborateurId;

    /**
     * The manager who decides the first stage — copied from the leave at creation rather than
     * re-derived, so a proposal keeps going to whoever granted the leave even if the role
     * hierarchy moves underneath it. Null for an ANNULATION, which has no manager stage.
     */
    @Column(name = "responsable_id")
    private Long responsableId;

    /**
     * Frozen at creation, for the reason {@link LeaveRequest#getPaysId()} gives: an employee
     * can transfer, and a country's HR queue should not lose their pending proposals when
     * they do.
     */
    @Column(name = "pays_id")
    private Long paysId;

    // ── What is being asked ──────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ModificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat", nullable = false, length = 24)
    private ModificationState etat;

    /** Why the employee is asking. The form does not cap it. */
    @Column(name = "motif", columnDefinition = "nvarchar(max)")
    private String motif;

    /**
     * The proposal. All three are null for an ANNULATION and all three are required for a
     * MODIFICATION — enforced by {@code CK_amr_dates} in V111 as well as by the service,
     * because the rows arriving from the timesheet are written by a different codebase.
     *
     * {@code nouveauTotal} is recomputed server-side from the dates, the entity's weekend
     * pattern and its holiday calendar — never taken from the client, for the reason
     * LeaveRequestService records about the original submission: a caller could otherwise
     * choose what their leave costs.
     */
    @Column(name = "nouvelle_date_debut")
    private LocalDate nouvelleDateDebut;

    @Column(name = "nouvelle_date_fin")
    private LocalDate nouvelleDateFin;

    @Column(name = "nouveau_total", precision = 5, scale = 2)
    private BigDecimal nouveauTotal;

    // ── How it ended ─────────────────────────────────────────────────────────

    @Column(name = "motif_refus", columnDefinition = "nvarchar(max)")
    private String motifRefus;

    /**
     * Who decided, per stage. Two columns rather than one {@code decided_by}: the stages are
     * decided by different people, and an audit trail that cannot say which of them acted is
     * of little use when a balance has moved.
     */
    @Column(name = "decided_by_manager")
    private Long decidedByManager;

    @Column(name = "decided_by_hr")
    private Long decidedByHr;

    /** The day the HR stage closed it, approved or refused. */
    @Column(name = "date_validation")
    private LocalDate dateValidation;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (etat == null && type != null) {
            etat = type.initialState();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
