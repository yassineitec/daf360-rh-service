package com.daf360.rh.dto.admin;

import lombok.Data;

import java.util.List;

@Data
public class RoleResponseDto {
    private Long         id;
    private String       frenchName;
    private Long         parentRoleId;
    private Boolean      showAll;
    private List<String> permissions;
    private String       parentRoleName;
    private int          userCount;
    private int          permissionCount;
}
