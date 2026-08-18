package com.daf360.rh.dto.asset;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/** One line of the employee's IT equipment history. */
@Data
@Builder
public class ItAssetAssignmentDto {

    private Long    id;
    private Long    employeeProfileId;

    private Long    assetTypeId;
    private String  assetTypeCode;
    private String  assetTypeLabelFr;
    private String  assetTypeLabelEn;

    private String  serialNumber;
    private String  brandModel;
    private String  assetTag;

    private LocalDate assignedAt;
    private LocalDate returnedAt;

    private String  conditionOnAssign;
    private String  conditionOnReturn;

    /** ASSIGNED | RETURNED | LOST | WRITTEN_OFF */
    private String  status;
    /** ONBOARDING | MANUAL | OFFBOARDING | IMPORT */
    private String  source;

    private Long    itProvisioningId;
    private Long    offboardingReturnId;

    private Long    assignedBy;
    private String  assignedByName;
    private Long    returnedBy;
    private String  returnedByName;

    private String  notes;

    /** Computed: `returnedAt IS NULL`. Saves the UI re-deriving the rule from the status. */
    private Boolean isCurrent;
    /** Computed: days held — to today while current, else assignedAt → returnedAt. */
    private Long    daysHeld;
}
