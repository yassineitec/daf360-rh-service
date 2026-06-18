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

    /** D3-102: contracts expiring within the alert window (CDD/CIVP/STAGE/DETACHEMENT only). */
    @Query("""
        SELECT c FROM EmployeeContract c
        WHERE c.isActive = true
        AND c.dateFinPrevue IS NOT NULL
        AND c.dateFinPrevue BETWEEN :today AND :alertDate
        AND c.contractTypeCode IN ('CDD', 'CIVP', 'STAGE', 'DETACHEMENT')
        """)
    List<EmployeeContract> findExpiringContracts(
        @Param("today") LocalDate today,
        @Param("alertDate") LocalDate alertDate);

    /** Contracts currently in trial period that have passed their end date. */
    @Query("""
        SELECT c FROM EmployeeContract c
        WHERE c.isActive = true
        AND c.currentStatusCode = 'PERIODE_ESSAI'
        AND c.dateFinPeriodeEssai <= :today
        """)
    List<EmployeeContract> findExpiredTrialPeriods(@Param("today") LocalDate today);
}
