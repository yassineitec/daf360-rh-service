package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class ContractDetailDto {

    private Long id;
    private Long employeeProfileId;
    private Long paysId;

    private String contractTypeCode;
    private String currentStatusCode;

    private LocalDate dateDebut;
    private LocalDate dateFinPrevue;
    private LocalDate dateFinReelle;
    private LocalDate dateFinPeriodeEssai;
    private Boolean periodeEssaiRenouvelee;
    private LocalDate dateFinPeRenouvellement;

    private String endReasonCode;
    private String endNotes;
    private String referenceContrat;

    // CIVP
    private String civpAnetiReference;
    private LocalDate civpConventionDate;

    // STAGE
    private String stageEcole;
    private Long stageTuteurId;
    private Boolean stageConventionSignee;

    // FREELANCE
    private BigDecimal freelanceTjm;
    private String freelanceDevise;
    private String freelanceSociete;

    // DETACHEMENT
    private Long detachementEntiteOrigineId;
    private Long detachementEntiteAccueilId;
    private LocalDate detachementRetourPrevu;

    // CDD
    private Integer cddRenouvellementCount;
    private Long cddContratParentId;
    private Long avenantParentId;

    private Boolean isActive;
    private Boolean isArchived;
    private Boolean dossierLocked;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
