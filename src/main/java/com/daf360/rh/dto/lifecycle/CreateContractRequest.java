package com.daf360.rh.dto.lifecycle;

import jakarta.validation.constraints.Min;
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

    /**
     * Préavis négocié in calendar days (V69), frozen onto the contract.
     *
     * Absent → resolved in this order: the candidate's accepted offer, then the employee's
     * grade default. Present → taken as typed and stamped MANUAL, including a deliberate 0.
     */
    @Min(value = 0, message = "Le préavis ne peut pas être négatif.")
    private Integer noticePeriodDays;

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
