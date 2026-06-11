package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.DemandeEtat;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class AutorisationResponseDto {
    private Long           id;
    private Long           collaborateurId;
    private Long           responsableId;
    private Long           responsableAdjointId;
    private LocalDate      date;
    private Long           nombreHeures;
    private DemandeEtat    etatDemande;
    private String         reason;
    private String         motifRefus;
    private OffsetDateTime dateValidation;
    private OffsetDateTime createdAt;
}
