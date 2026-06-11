package com.daf360.rh.dto.admin;

import lombok.Data;

@Data
public class UpdateRoleRequest {

    private String  frenchName;

    private Long    parentRoleId;

    private Boolean showAll;

    private Boolean forceRename = false;
}
