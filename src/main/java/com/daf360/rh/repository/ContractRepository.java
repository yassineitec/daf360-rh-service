package com.daf360.rh.repository;

import com.daf360.rh.domain.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContractRepository extends JpaRepository<Contract, Long> {
    List<Contract> findByEmployeeIdOrderByStartDateDesc(Long employeeId);
    Optional<Contract> findByEmployeeIdAndIsActiveTrue(Long employeeId);
    List<Contract> findByEndDateBeforeAndIsActiveTrue(LocalDate date);
}
