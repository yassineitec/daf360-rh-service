package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeLifecycleAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EmployeeLifecycleAlertRepository
        extends JpaRepository<EmployeeLifecycleAlert, Long> {

    /** D3-102: pending alerts whose alert_date has arrived. Uses IX_ELA_AlertDate filtered index. */
    @Query("SELECT a FROM EmployeeLifecycleAlert a WHERE a.isSent = false AND a.alertDate <= :today")
    List<EmployeeLifecycleAlert> findPendingAlerts(@Param("today") LocalDate today);

    /** Dedup guard: avoid planning the same alert type twice for a contract. */
    boolean existsByContractIdAndAlertType(Long contractId, String alertType);

    List<EmployeeLifecycleAlert> findByContractIdOrderByAlertDateAsc(Long contractId);

    List<EmployeeLifecycleAlert> findByEmployeeProfileIdOrderByAlertDateDesc(Long employeeProfileId);
}
