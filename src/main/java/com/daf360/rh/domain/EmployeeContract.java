package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Maps [dbo].[employee_contracts] — one row per contract per employee.
 * Created in Part 1 DB migration (V32).
 *
 * Self-referential FKs (cddContratParent, avenantParent) are nullable — no bootstrap issue.
 * NEVER hard-delete rows — set is_active=false + dossier_locked=true for terminal states.
 */
@Entity
@Table(name = "employee_contracts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_profile_id", nullable = false)
    private EmployeeProfile employeeProfile;

    @Column(name = "pays_id", nullable = false)
    private Long paysId;

    /** CDI | CDD | CIVP | STAGE | FREELANCE | DETACHEMENT */
    @Column(name = "contract_type_code", nullable = false, length = 30)
    private String contractTypeCode;

    /** RECRUTEMENT | PERIODE_ESSAI | ACTIF | SUSPENDU | FIN_CONTRAT | … (from configurable_list_values) */
    @Column(name = "current_status_code", nullable = false, length = 50)
    private String currentStatusCode;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin_prevue")
    private LocalDate dateFinPrevue;

    @Column(name = "date_fin_reelle")
    private LocalDate dateFinReelle;

    @Column(name = "date_fin_periode_essai")
    private LocalDate dateFinPeriodeEssai;

    @Column(name = "periode_essai_renouvelee", nullable = false)
    @Builder.Default
    private Boolean periodeEssaiRenouvelee = false;

    @Column(name = "date_fin_pe_renouvellement")
    private LocalDate dateFinPeRenouvellement;

    @Column(name = "end_reason_code", length = 50)
    private String endReasonCode;

    @Column(name = "end_notes", length = 1000, columnDefinition = "nvarchar(1000)")
    private String endNotes;

    @Column(name = "reference_contrat", length = 100, columnDefinition = "nvarchar(100)")
    private String referenceContrat;

    // ── CIVP ─────────────────────────────────────────────────────────────────
    @Column(name = "civp_aneti_reference", length = 100, columnDefinition = "nvarchar(100)")
    private String civpAnetiReference;

    @Column(name = "civp_convention_date")
    private LocalDate civpConventionDate;

    // ── STAGE ────────────────────────────────────────────────────────────────
    @Column(name = "stage_ecole", length = 255, columnDefinition = "nvarchar(255)")
    private String stageEcole;

    /** Raw FK to Users.id — no User entity in rh-service */
    @Column(name = "stage_tuteur_id")
    private Long stageTuteurId;

    @Column(name = "stage_convention_signee", nullable = false)
    @Builder.Default
    private Boolean stageConventionSignee = false;

    // ── FREELANCE ────────────────────────────────────────────────────────────
    @Column(name = "freelance_tjm", precision = 18, scale = 3)
    private BigDecimal freelanceTjm;

    @Column(name = "freelance_devise", length = 10)
    @Builder.Default
    private String freelanceDevise = "EUR";

    @Column(name = "freelance_societe", length = 255, columnDefinition = "nvarchar(255)")
    private String freelanceSociete;

    // ── DETACHEMENT ───────────────────────────────────────────────────────────
    @Column(name = "detachement_entite_origine_id")
    private Long detachementEntiteOrigineId;

    @Column(name = "detachement_entite_accueil_id")
    private Long detachementEntiteAccueilId;

    @Column(name = "detachement_retour_prevu")
    private LocalDate detachementRetourPrevu;

    // ── CDD renewal ───────────────────────────────────────────────────────────
    @Column(name = "cdd_renouvellement_count", nullable = false)
    @Builder.Default
    private Integer cddRenouvellementCount = 0;

    /** Previous CDD this one renews — nullable self-referential FK */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cdd_contrat_parent_id")
    private EmployeeContract cddContratParent;

    /** Parent contract this is an avenant of — nullable self-referential FK */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "avenant_parent_id")
    private EmployeeContract avenantParent;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_archived", nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    /** True for terminal states (INACTIF). Prevents further transitions. */
    @Column(name = "dossier_locked", nullable = false)
    @Builder.Default
    private Boolean dossierLocked = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "datetimeoffset(6)")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", columnDefinition = "datetimeoffset(6)")
    private OffsetDateTime updatedAt;
}
