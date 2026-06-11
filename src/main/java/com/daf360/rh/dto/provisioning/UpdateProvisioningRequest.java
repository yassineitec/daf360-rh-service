package com.daf360.rh.dto.provisioning;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class UpdateProvisioningRequest {

    // ── Hardware — V23: individual columns replaced by it_assets table ────────
    private List<ItAssetUpdateRequest> assets;

    // ── Licenses ──────────────────────────────────────────────────────────────
    private Boolean licenseOffice365;
    private Boolean licenseAutocad;
    private Boolean licenseRevit;
    private Boolean licenseAutodesk;
    private Boolean licenseKaspersky;

    @Size(max = 255)
    private String licenseOther;

    // ── Active Directory ──────────────────────────────────────────────────────
    private Boolean adAccountCreated;

    @Size(max = 100)
    private String adProfileType;

    private OffsetDateTime adAccountCreatedAt;

    // ── Notes ─────────────────────────────────────────────────────────────────
    @Size(max = 1000)
    private String notes;
}
