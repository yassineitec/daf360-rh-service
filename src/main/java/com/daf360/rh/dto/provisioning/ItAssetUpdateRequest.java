package com.daf360.rh.dto.provisioning;

import lombok.Data;

@Data
public class ItAssetUpdateRequest {
    /** Asset type code — e.g. LAPTOP, MOUSE, KEYBOARD. Must match it_asset_types.code. */
    private String assetTypeCode;
    private Boolean provided;
    private String serialNumber;
    private String brandModel;
    private String assetTag;
    private String status;
}
