package com.daf360.rh.repository;

import com.daf360.rh.domain.RegimeRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RegimeRoleAssignmentRepository extends JpaRepository<RegimeRoleAssignment, Long> {

    @Query("SELECT r FROM RegimeRoleAssignment r WHERE r.role.id = :roleId AND r.paysId = :paysId " +
           "AND r.isActive = true AND r.effectiveFrom <= :today " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :today)")
    Optional<RegimeRoleAssignment> findActiveForRoleAndPays(
            @Param("roleId") Long roleId,
            @Param("paysId") Long paysId,
            @Param("today") LocalDate today);

    @Query("SELECT r FROM RegimeRoleAssignment r WHERE r.paysId = :paysId AND r.isActive = true " +
           "ORDER BY r.role.frenchName ASC")
    List<RegimeRoleAssignment> findAllActiveForPays(@Param("paysId") Long paysId);

    boolean existsByRegimeIdAndIsActiveTrue(Long regimeId);

    long countByRegimeIdAndIsActiveTrue(Long regimeId);

    long countByPaysIdAndIsActiveTrue(Long paysId);

    @Modifying
    @Transactional
    @Query("UPDATE RegimeRoleAssignment r SET r.isActive = false, r.effectiveTo = :endDate " +
           "WHERE r.role.id = :roleId AND r.paysId = :paysId AND r.isActive = true")
    void deactivateForRoleAndPays(
            @Param("roleId") Long roleId,
            @Param("paysId") Long paysId,
            @Param("endDate") LocalDate endDate);
}
