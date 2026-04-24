package com.daf360.rh.repository;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.enums.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByEmail(String email);
    Optional<Employee> findByMatricule(String matricule);
    Optional<Employee> findByAzureOid(String azureOid);
    Page<Employee> findByStatusNot(EmployeeStatus status, Pageable pageable);
    @Query("SELECT COUNT(e) FROM Employee e WHERE e.status = 'ACTIVE'")
    long countActiveEmployees();
}
