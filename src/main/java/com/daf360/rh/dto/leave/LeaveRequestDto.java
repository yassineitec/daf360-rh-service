package com.daf360.rh.dto.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One congé, as every list and detail screen reads it.
 *
 * Names are resolved here rather than left as ids: the lists are the only place these rows
 * are read, and every one of them shows a person rather than a number.
 */
public record LeaveRequestDto(
        Long id,
        Long collaborateurId,
        String collaborateurName,
        Long responsableId,
        String responsableName,
        Long paysId,
        String type,
        String typeLabel,
        String category,
        String categoryLabel,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal totalJours,
        Boolean justificatif,
        String reason,
        String etatDemande,
        String motifRefus,
        LocalDate dateValidation,
        Long decidedBy,
        String decidedByName,
        /** Who FILED it. Differs from collaborateurId on a régularisation; null on old rows. */
        Long createdBy,
        String createdByName,
        OffsetDateTime createdAt
) {}
