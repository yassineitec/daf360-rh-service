package com.daf360.rh.dto.leave;

import com.daf360.rh.domain.enums.DemandeEtat;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class TeletravailResponseDto {
    private Long           id;
    private Long           collaborateurId;
    private Long           responsableId;
    private Long           responsableAdjointId;
    private LocalDate      date;               // converted from OffsetDateTime (Europe/Paris)
    private DemandeEtat    etatDemande;
    private String         reason;
    private String         motifRefus;
    private OffsetDateTime dateValidation;
    private OffsetDateTime createdAt;
}
