package com.daf360.rh.dto.lifecycle;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class ContractListDto {

    private Long id;
    private Long employeeProfileId;
    /** Lifecycle nature (CDI, CDD, CIVP, STAGE, FREELANCE, DETACHEMENT) — what the rules key on. */
    private String contractTypeCode;
    /** configurable_list_values.id of the CONTRACT_TYPE value — what employee_contracts stores. */
    private Long contractTypeId;
    /** The CONTRACT_TYPE value's label (« contrat », « CDI — Durée indéterminée »…). */
    private String contractTypeLabel;
    private String currentStatusCode;
    private LocalDate dateDebut;
    private LocalDate dateFinPrevue;
    private LocalDate dateFinPeriodeEssai;
    /** Trial renewed (decision RENEW_TRIAL) — the trial then ends on dateFinPeRenouvellement. */
    private Boolean periodeEssaiRenouvelee;
    private LocalDate dateFinPeRenouvellement;
    /** Times the trial was renewed. */
    private Long trialRenewalCount;
    /** The contract's resolved « Décider » rules (type, else nature — ContractRules). */
    private Boolean trialRenewable;
    private Integer trialMaxRenewals;
    /**
     * When no préavis was frozen on the contract: the employee's grade default, as a hint.
     * Null when the contract has its own figure or the grade has none.
     */
    private Integer defaultNoticeDays;
    private LocalDate dateFinReelle;
    /** Préavis frozen on this contract (V69); null before it existed. */
    private Integer noticePeriodDays;
    /** GRADE_DEFAULT | NEGOTIATED | MANUAL */
    private String noticePeriodSource;
    private Boolean isActive;
    private Boolean dossierLocked;
    private String referenceContrat;
    private OffsetDateTime createdAt;
}
