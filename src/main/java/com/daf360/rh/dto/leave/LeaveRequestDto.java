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
        /**
         * What the UI needs to draw this employee's face, and nothing more.
         *
         * `avatarUtils.getAvatarUrl(profileId, photoUrl, gender)` needs all three: the photo
         * endpoint is keyed by profile id, `photoUrl` carries the cache-busting token, and
         * gender picks the placeholder when there is no photo. All three are null for a user
         * with no employee profile, which is a real case — the initials then stand in.
         */
        Long collaborateurProfileId,
        String collaborateurPhotoUrl,
        String collaborateurGender,
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
