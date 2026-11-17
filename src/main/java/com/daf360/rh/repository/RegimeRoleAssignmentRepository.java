package com.daf360.rh.repository;

import com.daf360.rh.domain.RegimeRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * is_active = false means CANCELLED (never applied) — the entity's @SQLRestriction hides
 * those rows. An ENDED assignment stays active with an effective_to in the past (V127), so
 * every "in force" query below also tests the window.
 */
@Repository
public interface RegimeRoleAssignmentRepository extends JpaRepository<RegimeRoleAssignment, Long> {

    @Query("SELECT r FROM RegimeRoleAssignment r WHERE r.role.id = :roleId AND r.paysId = :paysId " +
           "AND r.isActive = true AND r.effectiveFrom <= :today " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :today) " +
           "ORDER BY r.effectiveFrom DESC, r.id DESC")
    List<RegimeRoleAssignment> findAllActiveForRoleAndPays(
            @Param("roleId") Long roleId,
            @Param("paysId") Long paysId,
            @Param("today") LocalDate today);

    /** The winner on {@code date} (newest start covering it). */
    default Optional<RegimeRoleAssignment> findActiveForRoleAndPays(Long roleId, Long paysId, LocalDate date) {
        return findAllActiveForRoleAndPays(roleId, paysId, date).stream().findFirst();
    }

    /** Every non-cancelled row of a role in an entity, whatever its window — for re-assignment. */
    @Query("SELECT r FROM RegimeRoleAssignment r WHERE r.role.id = :roleId AND r.paysId = :paysId " +
           "AND r.isActive = true ORDER BY r.effectiveFrom ASC, r.id ASC")
    List<RegimeRoleAssignment> findAllNonCancelledForRoleAndPays(
            @Param("roleId") Long roleId,
            @Param("paysId") Long paysId);

    /** In force on {@code date} or scheduled after it — what the admin tab lists. */
    @Query("SELECT r FROM RegimeRoleAssignment r WHERE r.paysId = :paysId AND r.isActive = true " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date) " +
           "ORDER BY r.role.frenchName ASC, r.effectiveFrom ASC")
    List<RegimeRoleAssignment> findCurrentAndUpcomingForPays(@Param("paysId") Long paysId,
                                                            @Param("date") LocalDate date);

    /** Every row covering {@code date} in an entity, newest first per role — batch resolution. */
    @Query("SELECT r FROM RegimeRoleAssignment r JOIN FETCH r.regime WHERE r.paysId = :paysId " +
           "AND r.isActive = true AND r.effectiveFrom <= :date " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date) " +
           "ORDER BY r.effectiveFrom DESC, r.id DESC")
    List<RegimeRoleAssignment> findCoveringForPays(@Param("paysId") Long paysId,
                                                  @Param("date") LocalDate date);

    /** A role assignment still running today or later uses the regime — blocks its deletion. */
    @Query("SELECT COUNT(r) FROM RegimeRoleAssignment r WHERE r.regime.id = :regimeId " +
           "AND r.isActive = true AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date)")
    long countUsingRegimeFrom(@Param("regimeId") Long regimeId, @Param("date") LocalDate date);

    /** Distinct roles with an assignment in force on {@code date} in an entity. */
    @Query("SELECT COUNT(DISTINCT r.role.id) FROM RegimeRoleAssignment r WHERE r.paysId = :paysId " +
           "AND r.isActive = true AND r.effectiveFrom <= :date " +
           "AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date)")
    long countRolesInForce(@Param("paysId") Long paysId, @Param("date") LocalDate date);
}
