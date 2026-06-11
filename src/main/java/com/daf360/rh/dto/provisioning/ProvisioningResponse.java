package com.daf360.rh.dto.provisioning;

import com.daf360.rh.domain.enums.ItProvisioningStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class ProvisioningResponse {

    // Provisioning identity
    private Long id;
    private Long candidateId;
    private Long userId;
    private ItProvisioningStatus status;

    // MS365 / email
    private String ms365Email;
    private OffsetDateTime ms365EmailCreatedAt;

    // Hardware — V23: individual columns replaced by it_assets table
    private List<ItAssetDto> assets;
    private String hardwareNotes;

    // Licenses
    private Boolean licenseOffice365;
    private Boolean licenseAutocad;
    private Boolean licenseRevit;
    private Boolean licenseAutodesk;
    private Boolean licenseKaspersky;
    private String licenseOther;

    // Active Directory
    private Boolean adAccountCreated;
    private String adProfileType;
    private OffsetDateTime adAccountCreatedAt;

    // Workflow
    private Long completedBy;
    private OffsetDateTime completedAt;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Candidate summary (enriched at query time)
    private String candidateFullName;
    private String appliedPosition;
    private Long paysId;
    private LocalDate expectedStartDate;
    private OffsetDateTime candidateAcceptedAt;
}
