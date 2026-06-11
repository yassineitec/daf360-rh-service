package com.daf360.rh.dto.candidate;

import com.daf360.rh.domain.enums.ItProvisioningStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ItProvisioningSummary {

    private Long id;
    private ItProvisioningStatus status;
    private String ms365Email;
    private OffsetDateTime ms365EmailCreatedAt;

    // Hardware — V23: individual columns replaced by it_assets table.
    // The full asset list is available via GET /api/hr/provisioning/{id}.
    // assetsProvided counts assets where provided = true (for quick summary on candidate card).
    private Integer assetsProvided;

    private Boolean licenseOffice365;
    private Boolean licenseAutocad;
    private Boolean licenseRevit;
    private Boolean licenseAutodesk;
    private Boolean licenseKaspersky;
    private String licenseOther;

    private Boolean adAccountCreated;
    private String adProfileType;
    private OffsetDateTime adAccountCreatedAt;

    private Long completedBy;
    private OffsetDateTime completedAt;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
