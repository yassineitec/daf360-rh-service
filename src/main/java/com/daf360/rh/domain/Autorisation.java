package com.daf360.rh.domain;

import com.daf360.rh.domain.enums.DemandeEtat;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Maps [autorisations] in DAF360_HR — owned by Timesheet, HR reads/creates only.
 * Schema verified 2026-05-31: 12 columns including responsable_adjoint_id.
 * DO NOT add columns or rename existing ones.
 */
@Entity
@Table(name = "autorisations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Autorisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collaborateur_id")
    private Long collaborateurId;

    @Column(name = "responsable_id")
    private Long responsableId;

    @Column(name = "responsable_adjoint_id")
    private Long responsableAdjointId;

    @Column(name = "date_autorisation", columnDefinition = "datetimeoffset")
    private OffsetDateTime dateAutorisation;

    @Column(name = "nombreHeures")
    private Long nombreHeures;

    @Enumerated(EnumType.STRING)
    @Column(name = "etatDemande", length = 255)
    private DemandeEtat etatDemande;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "motifRefus", length = 255)
    private String motifRefus;

    @Column(name = "created_at", updatable = false, columnDefinition = "datetimeoffset")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "datetimeoffset")
    private OffsetDateTime updatedAt;

    @Column(name = "dateValidation", columnDefinition = "datetimeoffset")
    private OffsetDateTime dateValidation;
}
