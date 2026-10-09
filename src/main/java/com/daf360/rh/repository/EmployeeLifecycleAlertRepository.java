package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeLifecycleAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeLifecycleAlertRepository
        extends JpaRepository<EmployeeLifecycleAlert, Long> {

    /**
     * The ledger row for one alert occurrence. Keyed by the TARGET date, not the contract
     * alone: a renewed CDD or a renewed trial has a new target date, hence a new occurrence
     * to announce, where the old (contract, type) key swallowed it.
     *
     * Several types because the legacy rows were written as CONTRACT_EXPIRY_30D — a row of
     * that type already sent for the same date must still count as sent.
     */
    Optional<EmployeeLifecycleAlert> findFirstByContractIdAndAlertTypeInAndTargetDateOrderByIdAsc(
        Long contractId, Collection<String> alertTypes, LocalDate targetDate);

    List<EmployeeLifecycleAlert> findByContractIdOrderByAlertDateAsc(Long contractId);

    List<EmployeeLifecycleAlert> findByEmployeeProfileIdOrderByAlertDateDesc(Long employeeProfileId);
}
