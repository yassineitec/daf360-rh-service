package com.daf360.rh.dto.provisioning;

import com.daf360.rh.domain.enums.ItProvisioningStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
public class ProvisioningListItem {

    private Long id;
    private Long candidateId;
    private String candidateFullName;
    private String appliedPosition;
    private Long paysId;
    private LocalDate expectedStartDate;
    private OffsetDateTime candidateAcceptedAt;

    private ItProvisioningStatus status;
    private String ms365Email;

    // Hardware summary — V23: flat boolean columns dropped; count provided assets instead
    private int assetsProvided;

    // License summary
    private Boolean licenseOffice365;
    private Boolean licenseAutocad;
    private Boolean licenseRevit;
    private Boolean licenseAutodesk;
    private Boolean licenseKaspersky;
    private String licenseOther;

    private OffsetDateTime createdAt;
}
