package com.daf360.rh.dto.provisioning;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ItAssetDto {

    private Long id;
    private String assetTypeCode;
    private String assetTypeLabelFr;
    private Boolean provided;
    private String serialNumber;
    private String brandModel;
    private String assetTag;
    private String status;
}
