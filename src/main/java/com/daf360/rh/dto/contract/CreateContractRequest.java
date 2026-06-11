package com.daf360.rh.dto.contract;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateContractRequest {

    @NotNull
    private Long idTypeContrat;

    /** CONTRAT_INITIAL or AVENANT */
    @NotNull
    private String typeDocument;

    @NotNull
    private LocalDate dateEffet;

    private LocalDate  dateFin;
    private BigDecimal salaireNet;
    private String     motif;
    private String     commentaire;
}
