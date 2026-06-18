package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateContractRequest {

    @NotNull
    private Long employeeProfileId;

    @NotNull
    private Long paysId;

    @NotBlank
    private String contractTypeCode;

    @NotNull
    private LocalDate dateDebut;

    private LocalDate dateFinPrevue;

    private String referenceContrat;

    /** Whether employee will be in a managerial role — affects CDI trial period length. */
    private boolean managerProfile;

    // ── CIVP ─────────────────────────────────────────────────────────────────
    private String civpAnetiReference;
    private LocalDate civpConventionDate;

    // ── STAGE ────────────────────────────────────────────────────────────────
    private String stageEcole;
    private Long stageTuteurId;
    private Boolean stageConventionSignee;

    // ── FREELANCE ────────────────────────────────────────────────────────────
    private BigDecimal freelanceTjm;
    private String freelanceDevise;
    private String freelanceSociete;

    // ── DETACHEMENT ───────────────────────────────────────────────────────────
    private Long detachementEntiteOrigineId;
    private Long detachementEntiteAccueilId;
    private LocalDate detachementRetourPrevu;

    // ── CDD renewal ───────────────────────────────────────────────────────────
    private Long cddContratParentId;
}
