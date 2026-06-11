package com.daf360.rh.dto.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PermissionCodeResponse {

    private String code;
    private String label;
}
