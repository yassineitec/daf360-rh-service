package com.daf360.rh.repository;

import com.daf360.rh.domain.RolePaysScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native SQL throughout, for the same two reasons as RolePermissionRepository:
 * the table name is PascalCase (Hibernate's CamelCaseToUnderscoresNamingStrategy would
 * mangle it), and save() on an all-@EmbeddedId entity merges instead of inserting.
 */
@Repository
public interface RolePaysScopeRepository
        extends JpaRepository<RolePaysScope, RolePaysScope.RolePaysScopeId> {

    @Query(value = "SELECT pays_id FROM RolePaysScope WHERE role_id = :roleId ORDER BY pays_id",
           nativeQuery = true)
    List<Long> findPaysIdsByRoleId(@Param("roleId") Long roleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM RolePaysScope WHERE role_id = :roleId", nativeQuery = true)
    void deleteByRoleId(@Param("roleId") Long roleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO RolePaysScope (role_id, pays_id) VALUES (:roleId, :paysId)",
           nativeQuery = true)
    void insertPaysId(@Param("roleId") Long roleId, @Param("paysId") Long paysId);
}
