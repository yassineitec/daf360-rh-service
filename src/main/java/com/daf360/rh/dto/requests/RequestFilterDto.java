package com.daf360.rh.dto.requests;

import com.daf360.rh.domain.enums.RequestStatus;
import lombok.Data;

@Data
public class RequestFilterDto {
    private Long          profileId;
    /** Un ou plusieurs pays (drapeaux de la navbar) ; vide = tout le périmètre du rôle. */
    private java.util.List<Long> paysIds;
    private RequestStatus status;
    private Long          typeId;
}
