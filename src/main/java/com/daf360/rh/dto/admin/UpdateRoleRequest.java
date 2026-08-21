package com.daf360.rh.dto.admin;

import lombok.Data;

import java.util.List;

@Data
public class UpdateRoleRequest {

    private String  frenchName;

    private Long    parentRoleId;

    private Boolean showAll;

    /** OWN | LIST | ALL. null = leave the mode untouched. */
    private String paysScopeMode;

    /** Full replacement of the role's country list. null = leave it untouched (so existing
     *  PATCH callers that know nothing about scope keep working); an empty list clears it. */
    private List<Long> paysScope;

    private Boolean forceRename = false;
}
