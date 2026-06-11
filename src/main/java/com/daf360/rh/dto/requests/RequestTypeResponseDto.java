package com.daf360.rh.dto.requests;

import com.daf360.rh.domain.enums.RequestCategory;
import lombok.Data;

@Data
public class RequestTypeResponseDto {
    private Long            id;
    private Long            paysId;
    private String          typeCode;
    private String          displayNameFr;
    private String          displayNameEn;
    private String          description;
    private RequestCategory category;
    private String          approvalLevel;
    private Integer         defaultSlaDays;
    private String          documentTemplateUrl;
    private Boolean         isActive;
}
