package com.daf360.rh.dto.asset;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** Closes an open ledger row: the item came back, was lost, or was written off. */
@Data
public class ReturnAssetAssignmentRequest {

    @NotNull
    private LocalDate returnedAt;

    /** NEUF | BON_ETAT | USAGE | EN_REPARATION | DEFECTUEUX | PERDU */
    private String conditionOnReturn;

    /**
     * RETURNED (default) | LOST | WRITTEN_OFF. All three close the row — the difference is
     * what to say about the object afterwards, and a write-off is an accounting event.
     */
    private String status;

    private String notes;
}
