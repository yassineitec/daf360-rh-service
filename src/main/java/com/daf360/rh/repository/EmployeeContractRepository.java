package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EmployeeContractRepository extends JpaRepository<EmployeeContract, Long> {

    List<EmployeeContract> findByEmployeeProfileIdAndIsActiveTrue(Long employeeProfileId);

    List<EmployeeContract> findByEmployeeProfileIdOrderByCreatedAtDesc(Long employeeProfileId);

    long countByEmployeeProfileIdAndIsActiveTrue(Long employeeProfileId);

    /**
     * D3-102: active contracts whose planned end falls in [today, horizon].
     *
     * Any type with a planned end — a CDI has none and so never matches. No type filter in
     * SQL anyway: contract_type_code holds a CONTRACT_TYPE list id, not a code. The horizon is
     * the LARGEST lead time an admin can configure; the per-(pays, nature) lead time is applied
     * by the job, so changing it takes effect on the next run instead of being frozen into a
     * pre-planned row.
     */
    @Query("""
        SELECT c FROM EmployeeContract c
        WHERE c.isActive = true
        AND c.dateFinPrevue IS NOT NULL
        AND c.dateFinPrevue BETWEEN :today AND :horizon
        """)
    List<EmployeeContract> findExpiringContracts(
        @Param("today") LocalDate today,
        @Param("horizon") LocalDate horizon);

    /**
     * Active contracts still before trial validation whose trial end (initial or renewed)
     * falls in [today, horizon]. The job picks the effective date of the two.
     */
    @Query("""
        SELECT c FROM EmployeeContract c
        WHERE c.isActive = true
        AND c.currentStatusCode IN ('RECRUTEMENT', 'PERIODE_ESSAI')
        AND ((c.dateFinPeriodeEssai BETWEEN :today AND :horizon)
          OR (c.periodeEssaiRenouvelee = true AND c.dateFinPeRenouvellement BETWEEN :today AND :horizon))
        """)
    List<EmployeeContract> findTrialPeriodsEnding(
        @Param("today") LocalDate today,
        @Param("horizon") LocalDate horizon);

    /** Contracts currently in trial period that have passed their end date. */
    @Query("""
        SELECT c FROM EmployeeContract c
        WHERE c.isActive = true
        AND c.currentStatusCode = 'PERIODE_ESSAI'
        AND c.dateFinPeriodeEssai <= :today
        """)
    List<EmployeeContract> findExpiredTrialPeriods(@Param("today") LocalDate today);
}
