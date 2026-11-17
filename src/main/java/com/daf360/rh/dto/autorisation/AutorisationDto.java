package com.daf360.rh.dto.autorisation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** One autorisation as the four screens read it. Names resolved server-side, as for congés. */
@Data
@Builder
public class AutorisationDto {

    private Long id;

    private Long collaborateurId;
    private String collaborateurNom;
    private String collaborateurAvatarUrl;
    private Long responsableId;
    private String responsableNom;
    private Long paysId;

    private LocalDate dateAutorisation;
    private LocalTime startTime;
    private LocalTime endTime;

    /** Minutes. The UI renders "1 h 30" from it rather than storing a formatted string. */
    private Integer dureeMinutes;

    private String etatDemande;
    private String etatLabel;
    private String reason;
    private String motifRefus;
    private String motifAnnulation;

    private LocalDate dateValidation;
    private Long decidedBy;
    private String decidedByNom;
    private Long createdBy;
    private String createdByNom;
    /** True when somebody other than the employee filed it — a régularisation. */
    private Boolean settled;

    private OffsetDateTime createdAt;
}
