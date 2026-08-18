package com.daf360.rh.dto.asset;

import lombok.Data;

import java.time.LocalDate;

/**
 * Corrects a ledger row. Only the descriptive fields and the assignment date: the
 * return is done through the return endpoint so that closing a row always goes
 * through the same rules (and the same audit entry).
 *
 * Every field is nullable and null means "leave alone" — a PATCH, not a PUT. Clearing
 * a text field is done with an empty string.
 */
@Data
public class UpdateAssetAssignmentRequest {

    private Long      assetTypeId;
    private String    serialNumber;
    private String    brandModel;
    private String    assetTag;
    private LocalDate assignedAt;
    private String    conditionOnAssign;
    private String    notes;
}
