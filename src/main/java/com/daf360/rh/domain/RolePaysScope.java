package com.daf360.rh.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * One row per (role, country) the role is allowed to see — see V74__role_pays_scope.sql.
 *
 * Only meaningful when Roles.showAll = 0; showAll = 1 means "every country" and the rows
 * here are ignored. No rows at all falls back to the user's own Users.pays_id, which is
 * what every pre-V74 role does.
 *
 * Modelled exactly like RolePermission: an all-@EmbeddedId entity on a join table with no
 * surrogate key. That shape makes Hibernate's save() a no-op (a non-null id means merge),
 * so RolePaysScopeRepository does its writes in native SQL for the same reason.
 */
@Entity
@Table(name = "RolePaysScope")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RolePaysScope {

    @EmbeddedId
    private RolePaysScopeId id;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RolePaysScopeId implements Serializable {

        @Column(name = "role_id")
        private Long roleId;

        @Column(name = "pays_id")
        private Long paysId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RolePaysScopeId other)) return false;
            return Objects.equals(roleId, other.roleId)
                && Objects.equals(paysId, other.paysId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, paysId);
        }
    }
}
