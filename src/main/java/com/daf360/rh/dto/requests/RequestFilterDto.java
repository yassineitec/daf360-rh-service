package com.daf360.rh.dto.requests;

import com.daf360.rh.domain.enums.RequestStatus;
import lombok.Data;

@Data
public class RequestFilterDto {
    private Long          profileId;
    private Long          paysId;
    private RequestStatus status;
    private Long          typeId;
}
