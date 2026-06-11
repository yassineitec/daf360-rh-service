package com.daf360.rh.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PermissionGroupResponse {

    private String                    groupName;
    private List<PermissionCodeResponse> permissions;
}
