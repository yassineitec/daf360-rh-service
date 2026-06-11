package com.daf360.rh.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Full replacement of a role's permissions.
 * Every value must be in RoleService.ALLOWED_PERMISSIONS
 * (CK_RolePermissions_Permission DB constraint, 32 values).
 */
@Data
public class UpdatePermissionsDto {

    @NotNull
    private List<String> permissions;
}
