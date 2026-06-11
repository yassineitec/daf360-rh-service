package com.daf360.rh.repository;

import com.daf360.rh.domain.LeaveBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    List<LeaveBalance> findByEmployeeProfileIdAndAnnee(Long employeeProfileId, Integer annee);

    Optional<LeaveBalance> findByEmployeeProfileIdAndAnneeAndLeaveType(
            Long employeeProfileId, Integer annee, String leaveType);

    boolean existsByEmployeeProfileIdAndAnneeAndLeaveType(
            Long employeeProfileId, Integer annee, String leaveType);
}
