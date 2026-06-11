package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Maps the shared [absences] table in DAF360_HR.
 *
 * ⚠ This table is owned by the Timesheet application — do NOT add or rename columns.
 *   Every field here corresponds to an existing column; ddl-auto:update must not ALTER
 *   this table structure.
 *
 * Key naming conventions in this table (French/camelCase, NOT English underscore):
 *   dateDebut / dateFin      — stored as datetimeoffset (UTC midnight of local date)
 *   etatDemande              — leave state (EN_ATTENTE | VALIDE | REFUSE | ARCHIVE)
 *   collaborateur_id         — FK → Users.id (the requesting employee)
 *   responsable_id           — FK → Users.id (direct manager)
 *   responsable_adjoint_id   — FK → Users.id (deputy / HR validator)
 *
 * Do NOT extend AuditableEntity: this table has created_at/updated_at as datetimeoffset
 * but has no created_by/updated_by columns.
 */
@Entity
@Table(name = "absences")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK → Users.id (the employee submitting the request). */
    @Column(name = "collaborateur_id")
    private Long employeeId;

    /** FK → Users.id (direct manager). */
    @Column(name = "responsable_id")
    private Long managerValidatorId;

    /** FK → Users.id (deputy manager / HR). */
    @Column(name = "responsable_adjoint_id")
    private Long hrValidatorId;

    /** FK → leave_balances.id — balance deducted on VALIDE. */
    @Column(name = "leave_balance_id")
    private Long leaveBalanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 255)
    private LeaveType leaveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 255)
    private LeaveCategory category;

    /**
     * UTC midnight of local date — use AT TIME ZONE 'Romance Standard Time' in native SQL
     * to get the local date for display.
     */
    @Column(name = "dateDebut", columnDefinition = "datetimeoffset")
    private OffsetDateTime startDate;

    @Column(name = "dateFin", columnDefinition = "datetimeoffset")
    private OffsetDateTime endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "etatDemande", length = 255)
    private DemandeEtat etatDemande;

    @Column(name = "dateValidation", columnDefinition = "datetimeoffset")
    private OffsetDateTime dateValidation;

    /** Employee's reason / comment. */
    @Column(name = "reason", length = 255)
    private String comment;

    /** HR rejection reason. */
    @Column(name = "motifRefus", length = 255)
    private String rejectionReason;

    /** Total calendar days (inclusive). */
    @Column(name = "totalJours", precision = 6, scale = 2)
    private BigDecimal totalJours;

    /** Working (business) days only. */
    @Column(name = "nombre_jours_ouvres", precision = 6, scale = 2)
    private BigDecimal workingDays;

    /** Whether a supporting document was attached. */
    @Column(name = "justificatif")
    private Boolean justificatif;

    @Column(name = "created_at", updatable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;
}
