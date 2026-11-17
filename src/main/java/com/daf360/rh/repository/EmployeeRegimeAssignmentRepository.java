package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeRegimeAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmployeeRegimeAssignmentRepository extends JpaRepository<EmployeeRegimeAssignment, Long> {

    /** Active rows covering {@code date}, newest first — the first one is the winner. */
    @Query("SELECT a FROM EmployeeRegimeAssignment a WHERE a.employeeProfileId = :profileId "
         + "AND a.isActive = true AND a.effectiveFrom <= :date "
         + "AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date) "
         + "ORDER BY a.effectiveFrom DESC, a.id DESC")
    List<EmployeeRegimeAssignment> findCovering(@Param("profileId") Long profileId,
                                                @Param("date") LocalDate date);

    /** Every active (non-cancelled) row of an employee, past ones included. */
    @Query("SELECT a FROM EmployeeRegimeAssignment a WHERE a.employeeProfileId = :profileId "
         + "AND a.isActive = true ORDER BY a.effectiveFrom DESC, a.id DESC")
    List<EmployeeRegimeAssignment> findAllActive(@Param("profileId") Long profileId);

    /** Rows in force on {@code date} or starting after it — what RH can still act on. */
    @Query("SELECT a FROM EmployeeRegimeAssignment a WHERE a.employeeProfileId = :profileId "
         + "AND a.isActive = true AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date) "
         + "ORDER BY a.effectiveFrom ASC, a.id ASC")
    List<EmployeeRegimeAssignment> findCurrentAndUpcoming(@Param("profileId") Long profileId,
                                                          @Param("date") LocalDate date);

    /** Active rows of a given source — onboarding re-runs replace their own row. */
    List<EmployeeRegimeAssignment> findByEmployeeProfileIdAndSourceAndIsActiveTrue(Long profileId, String source);

    /** For the overview: every row covering {@code date} for the non-deleted profiles of an entity. */
    @Query("SELECT a FROM EmployeeRegimeAssignment a WHERE a.isActive = true "
         + "AND a.effectiveFrom <= :date AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date) "
         + "AND a.employeeProfileId IN (SELECT p.id FROM EmployeeProfile p "
         + "                            WHERE p.paysId = :paysId AND p.deleted = false) "
         + "ORDER BY a.employeeProfileId, a.effectiveFrom DESC, a.id DESC")
    List<EmployeeRegimeAssignment> findCoveringForPays(@Param("paysId") Long paysId,
                                                       @Param("date") LocalDate date);

    /** Employees (non-deleted) still using a regime today or later — blocks its deletion. */
    @Query("SELECT COUNT(DISTINCT a.employeeProfileId) FROM EmployeeRegimeAssignment a "
         + "WHERE a.regime.id = :regimeId AND a.isActive = true "
         + "AND (a.effectiveTo IS NULL OR a.effectiveTo >= :date) "
         + "AND a.employeeProfileId IN (SELECT p.id FROM EmployeeProfile p WHERE p.deleted = false)")
    long countEmployeesUsingFrom(@Param("regimeId") Long regimeId, @Param("date") LocalDate date);
}
