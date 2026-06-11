package com.daf360.rh.repository;

import com.daf360.rh.domain.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository
        extends JpaRepository<RolePermission, RolePermission.RolePermissionId> {

    // Native SQL — bypasses Hibernate naming strategy entirely
    @Query(value = "SELECT permission FROM RolePermissions WHERE role_id = :roleId ORDER BY permission",
           nativeQuery = true)
    List<String> findPermissionsByRoleId(@Param("roleId") Long roleId);

    // clearAutomatically = true invalidates the JPA first-level cache after the native DELETE,
    // ensuring subsequent permRepo.save() calls INSERT new rows instead of silently no-op.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM RolePermissions WHERE role_id = :roleId",
           nativeQuery = true)
    void deleteByRoleId(@Param("roleId") Long roleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM RolePermissions WHERE role_id = :roleId AND permission = :permission",
           nativeQuery = true)
    void deleteByRoleIdAndPermission(@Param("roleId") Long roleId, @Param("permission") String permission);
}
