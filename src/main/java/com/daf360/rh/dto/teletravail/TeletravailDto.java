package com.daf360.rh.dto.teletravail;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One télétravail request as the screens read it. Names resolved server-side, as for congés. */
@Data
@Builder
public class TeletravailDto {

    private Long id;

    private Long collaborateurId;
    private String collaborateurNom;
    private String collaborateurAvatarUrl;
    private Long responsableId;
    private String responsableNom;
    private Long paysId;

    private LocalDate dateTeletravail;
    /** FULL_DAY | HALF_DAY_MORNING | HALF_DAY_AFTERNOON. */
    private String category;
    private String categoryLabel;
    /** 1.0 or 0.5. */
    private BigDecimal jours;

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
    /** True when soldeTeletravail was debited for it — archiving it gives the days back. */
    private Boolean balanceDebited;

    private OffsetDateTime createdAt;
}
