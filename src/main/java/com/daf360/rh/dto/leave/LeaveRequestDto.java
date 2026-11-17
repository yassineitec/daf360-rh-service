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
        /**
         * The optional second approver, carried so an EDIT can put it back.
         *
         * Absent until the self-service edit form existed, and its absence was a silent data
         * loss: the edit endpoint takes responsableAdjointId in its body, so a form that
         * never received the current value would send null and clear a deputy the employee
         * had chosen. No name beside it — nothing displays this, it only round-trips.
         */
        Long responsableAdjointId,
        Long paysId,
        String type,
        String typeLabel,
        String category,
        String categoryLabel,
        LocalDate dateDebut,
        LocalDate dateFin,
        BigDecimal totalJours,
        Boolean justificatif,
        /** The attached file's document id, or null when nothing was uploaded (V110). */
        Long justificatifDocumentId,
        String reason,
        String etatDemande,
        String motifRefus,
        /** Why it was cancelled — the employee's own account, never an approver's. V112. */
        String motifAnnulation,
        LocalDate dateValidation,
        Long decidedBy,
        String decidedByName,
        /** Who FILED it. Differs from collaborateurId on a régularisation; null on old rows. */
        Long createdBy,
        String createdByName,
        OffsetDateTime createdAt
) {}
