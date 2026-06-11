package com.daf360.rh.dto.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContractHistoryDto {
    private Long       id;
    private Long       idCollaborateur;
    private Long       idTypeContrat;
    private String     typeContratCode;
    private String     typeContratLabelFr;
    private String     typeDocument;       // CONTRAT_INITIAL | AVENANT
    private LocalDate  dateEffet;
    private LocalDate  dateFin;
    private BigDecimal salaireNet;
    private String     motif;
    private String     commentaire;
    private Long       createdBy;
    private OffsetDateTime dateCreation;
    private boolean    isActive;           // computed: dateFin IS NULL or dateFin >= today
}
