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
    /** Préavis frozen on this contract (V69); null before it existed. */
    private Integer noticePeriodDays;
    /** GRADE_DEFAULT | NEGOTIATED | MANUAL */
    private String noticePeriodSource;
    private Boolean isActive;
    private Boolean dossierLocked;
    private String referenceContrat;
    private OffsetDateTime createdAt;
}
