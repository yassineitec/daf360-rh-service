package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[leave_requests] in DAF360_HR — one congé request.
 *
 * Ported from the timesheet application's {@code Absence}, which extended a shared
 * {@code Demande} @MappedSuperclass alongside {@code Autorisation} and {@code Teletravail}.
 * That base class is deliberately NOT reproduced here: it had no table of its own, so its
 * columns were copied into all three tables anyway, and an inheritance hierarchy for two
 * modules that have not moved yet would fix their shape before either is designed. When
 * autorisations and télétravail follow, whatever the three genuinely share can be lifted
 * out then, with all three in view.
 *
 * THE EXISTING TABLE, CLEANED — NOT A NEW ONE
 * -----------------------------------------------------------------------------
 * This maps [dbo].[absences], the table the earlier copy already landed 716 rows into.
 *
 * That table arrived carrying BOTH column spellings at once — dateDebut and date_debut,
 * etatDemande and etat_demande, totalJours and total_jours — plus two columns
 * (leave_balance_id, nombre_jours_ouvres) no code on either side reads, with dates as
 * DATETIMEOFFSET at +01:00. `db-rh/seed/V104__absences_align_to_entity.sql` resolves that:
 * it drops whichever spelling is empty, renames the surviving one to snake_case, and
 * converts the three date columns to DATE.
 *
 * Creating a fresh table was considered and rejected: prod and testing run
 * `ddl-auto: none`, so DDL is hand-written either way, and a second table meaning the same
 * thing would have to be kept in step with this one for as long as both existed.
 *
 * WHY DATES ARE LocalDate
 * -----------------------------------------------------------------------------
 * A leave day is a calendar day, not an instant — the same reasoning {@link Mission} records
 * for its own start/end. The existing copy stores them as DATETIMEOFFSET at +01:00, and every
 * DAF360 container is pinned to UTC on purpose: midnight at +01:00 normalises to 23:00 the
 * PREVIOUS day, moving a congé a day earlier and landing a half-day on the wrong half. DATE
 * cannot express that error.
 *
 * WHY collaborateur/responsable ARE Users.id
 * -----------------------------------------------------------------------------
 * Not employee_profile_id, for the reason {@link Mission} gives: the rule deciding who may
 * approve for whom IS the role hierarchy (Users.role_id → Roles.parent_role_id), walked
 * upward within the same pays. There is no manager column anywhere — portal
 * {@code Users.manager_id} is commented out — so the approver set is derived, and it is
 * derived from user rows. The self-service screens also only ever know the caller's user id.
 */
@Entity
@Table(
    name = "absences",
    indexes = {
        // The three access paths every list screen uses. Without these the employee's own
        // history, the manager's pending queue and the country-wide history all table-scan.
        // Declared here for the dev profile; V104 creates them for prod and testing, which
        // run ddl-auto: none.
        @Index(name = "IX_absences_collaborateur", columnList = "collaborateur_id, date_debut"),
        @Index(name = "IX_absences_responsable",   columnList = "responsable_id, etat_demande"),
        @Index(name = "IX_absences_etat_date",     columnList = "etat_demande, date_debut")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Who ──────────────────────────────────────────────────────────────────

    /** The employee taking the leave. A {@code Users.id}. */
    @Column(name = "collaborateur_id", nullable = false)
    private Long collaborateurId;

    /**
     * The approver, chosen by the employee at submission from the set the role hierarchy
     * allows. Nullable only because a régularisation created by HR under SETTLE_LEAVES has
     * no requester-chosen approver.
     */
    @Column(name = "responsable_id")
    private Long responsableId;

    /** Optional second approver; either may decide. */
    @Column(name = "responsable_adjoint_id")
    private Long responsableAdjointId;

    /**
     * Pays of the employee, copied at creation rather than joined.
     *
     * The country-wide history and the HR statistics both filter on it, and the employee's
     * country can change after the fact (a transfer). Copying freezes the request in the
     * country it was taken in, which is what an HR report of that country should show.
     */
    @Column(name = "pays_id")
    private Long paysId;

    // ── What ─────────────────────────────────────────────────────────────────

    /**
     * The absence type CODE, not an enum.
     *
     * The catalogue is a table ({@link AbsenceType}) that HR administers, so the set of valid
     * values changes without a deployment. Stored as the code rather than a foreign key
     * because that is what the timesheet writes and what 716 migrated rows already hold —
     * and because a retired type must still resolve for the requests filed under it.
     */
    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 24)
    private LeaveCategory category;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    /** Equal to {@code dateDebut} for the three single-day categories. */
    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    /**
     * Billable days, to a half. DECIMAL not double: this is subtracted from a balance and
     * compared against a tolerance, and 0.5 has no exact binary representation.
     */
    @Column(name = "total_jours", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalJours;

    /** Whether a supporting document was promised. Not whether one was received. */
    @Column(name = "justificatif")
    private Boolean justificatif;

    /**
     * The uploaded supporting document, in `employee_documents` (V110).
     *
     * A plain id and NOT a JPA relation, on purpose: `employee_documents` is soft-deleted, so
     * a managed association would either block the delete or cascade it, and a request whose
     * document was removed must still load and simply show no file. The service treats a
     * dangling id as absent.
     *
     * Distinct from {@link #justificatif}, which only ever meant "the employee says they have
     * one". 716 migrated rows carry that flag with nothing behind it; a null here on one of
     * them reads correctly as "claimed, not attached".
     */
    @Column(name = "justificatif_document_id")
    private Long justificatifDocumentId;

    /** The employee's stated reason. */
    @Column(name = "reason", columnDefinition = "NVARCHAR(MAX)")
    private String reason;

    // ── Decision ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_demande", nullable = false, length = 16)
    private DemandeEtat etatDemande;

    /** Set when refused, cleared when approved. Never both. */
    @Column(name = "motif_refus", columnDefinition = "NVARCHAR(MAX)")
    private String motifRefus;

    /** The day the decision was taken — a calendar day, like the leave dates. */
    @Column(name = "date_validation")
    private LocalDate dateValidation;

    /** Who decided. Null while EN_ATTENTE. Not necessarily the responsable: HR may settle. */
    @Column(name = "decided_by")
    private Long decidedBy;

    // ── Audit ────────────────────────────────────────────────────────────────

    /**
     * Who FILED the request, which is not always who it is for.
     *
     * Equal to {@code collaborateurId} on an ordinary submission; different on a
     * régularisation, where HR files a congé on someone else's behalf. That difference IS
     * the definition of a régularisation — see {@code LeaveRequestRepository.findSettled}.
     *
     * Null on the rows migrated from the timesheet that the V108 back-fill could not claim.
     * Null means "unknown", not "filed by the employee": inventing an author for a historical
     * row would make the settle list lie about who did what.
     */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Defaults applied here rather than in the service because a régularisation, a normal
     * submission and a data import all have to land in the same shape.
     *
     * EN_ATTENTE is the only state a request may be born in — approval is an act, never an
     * initial condition.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (etatDemande == null) {
            etatDemande = DemandeEtat.EN_ATTENTE;
        }
        if (dateFin == null) {
            dateFin = dateDebut;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Only a pending request is editable by its owner — see LeaveRequestService.update. */
    public boolean isPending() {
        return etatDemande == DemandeEtat.EN_ATTENTE;
    }

    /** True once a decision has been taken, whichever way. */
    public boolean isDecided() {
        return etatDemande == DemandeEtat.VALIDE || etatDemande == DemandeEtat.REFUSE;
    }
}
