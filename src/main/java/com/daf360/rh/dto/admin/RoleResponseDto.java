package com.daf360.rh.dto.admin;

import lombok.Data;

import java.util.List;

@Data
public class RoleResponseDto {
    private Long         id;
    private String       frenchName;
    private Long         parentRoleId;
    /** Legacy flag, kept in sync with paysScopeMode == ALL. */
    private Boolean      showAll;
    private List<String> permissions;
    /** OWN | LIST | ALL — how paysScope below is interpreted. */
    private String       paysScopeMode;
    /**
     * Countries this role may see. Only meaningful when paysScopeMode is LIST; in OWN mode
     * these rows are retained but ignored (each holder sees their own country instead).
     */
    private List<Long>   paysScope;
    private String       parentRoleName;
    private int          userCount;
    private int          permissionCount;
}
