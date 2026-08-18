package com.daf360.rh.dto.asset;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** Hands a new item to an employee — one row in the ledger, still open. */
@Data
public class CreateAssetAssignmentRequest {

    @NotNull
    private Long assetTypeId;

    @NotNull
    private LocalDate assignedAt;

    private String serialNumber;
    private String brandModel;
    private String assetTag;

    /** NEUF | BON_ETAT | USAGE | EN_REPARATION | DEFECTUEUX. Defaults to BON_ETAT. */
    private String conditionOnAssign;

    private String notes;
}
