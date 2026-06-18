package com.daf360.rh.repository;

import com.daf360.rh.domain.EmployeeLifecycleTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeLifecycleTransitionRepository
        extends JpaRepository<EmployeeLifecycleTransition, Long> {

    /** D3-104: full chronological history for a single contract. */
    List<EmployeeLifecycleTransition> findByContractIdOrderByTriggeredAtAsc(Long contractId);

    /** Full history for an employee across all their contracts. */
    List<EmployeeLifecycleTransition> findByEmployeeProfileIdOrderByTriggeredAtAsc(Long employeeProfileId);
}
