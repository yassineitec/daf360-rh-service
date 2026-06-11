package com.daf360.rh.domain;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Maps the shared [RolePermissions] table.
 *
 * ⚠ CRITICAL CONSTRAINT: CK_RolePermissions_Permission allows ONLY the 32 values
 * defined in RoleService.ALLOWED_PERMISSIONS. Inserting any other value will fail.
 */
@Entity
@Table(name = "RolePermissions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id;

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RolePermissionId implements Serializable {
        @Column(name = "role_id")
        private Long roleId;

        @Column(name = "permission", length = 255)
        private String permission;
    }
}
