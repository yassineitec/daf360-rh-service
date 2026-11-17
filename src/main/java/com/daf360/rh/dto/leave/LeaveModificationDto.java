package com.daf360.rh.dto.leave;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One modification proposal as the three screens show it.
 *
 * CARRIES THE ORIGINAL CONGÉ ALONGSIDE THE PROPOSAL. Every one of the three queues shows
 * "from X to Y" against "they want A to B" — an approver cannot decide on the proposed dates
 * without the dates being replaced. Sending only the proposal would make each row a second
 * request to resolve what it is about.
 *
 * Labels are resolved server-side from the caller's language, like the congé DTOs: the
 * states and types carry their own French and English text, and duplicating that table in
 * every frontend is how the two fall out of step.
 */
@Data
@Builder
public class LeaveModificationDto {

    private Long id;

    // ── The proposal ─────────────────────────────────────────────────────────

    private String type;        // MODIFICATION | ANNULATION
    private String typeLabel;
    private String etat;        // PENDING_MANAGER | PENDING_HR | APPROVED | REJECTED
    private String etatLabel;

    private LocalDate nouvelleDateDebut;
    private LocalDate nouvelleDateFin;
    private BigDecimal nouveauTotal;

    /**
     * What the change costs or gives back, proposed total minus current — negative means the
     * employee gets days back. Computed here rather than in the browser so the figure an
     * approver sees is the figure the approval will apply.
     */
    private BigDecimal deltaJours;

    private String motif;
    private String motifRefus;

    // ── The congé it stands against ──────────────────────────────────────────

    private Long absenceId;
    private String absenceType;
    private String absenceTypeLabel;
    private LocalDate absenceDateDebut;
    private LocalDate absenceDateFin;
    private BigDecimal absenceTotalJours;

    // ── Who ──────────────────────────────────────────────────────────────────

    private Long collaborateurId;
    private String collaborateurNom;
    private String collaborateurAvatarUrl;
    private Long responsableId;
    private String responsableNom;

    // ── When ─────────────────────────────────────────────────────────────────

    private OffsetDateTime createdAt;
    private LocalDate dateValidation;
}
