package com.daf360.rh.repository;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.AbsenceType;
import com.daf360.rh.domain.enums.LeaveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findByEmployeeIdOrderByStartDateDesc(Long employeeId);
    Page<LeaveRequest> findByStatus(LeaveStatus status, Pageable pageable);

    @Query("SELECT SUM(lr.workingDays) FROM LeaveRequest lr " +
           "WHERE lr.employeeId = :employeeId AND lr.absenceType = :type " +
           "AND lr.status = 'HR_APPROVED' AND YEAR(lr.startDate) = :year")
    Integer sumApprovedDaysByTypeAndYear(@Param("employeeId") Long employeeId,
                                         @Param("type") AbsenceType type,
                                         @Param("year") int year);
}
