package com.daf360.rh.repository;

import com.daf360.rh.domain.PaySlip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaySlipRepository extends JpaRepository<PaySlip, Long> {
    List<PaySlip> findByEmployeeIdOrderByYearPeriodDescMonthPeriodDesc(Long employeeId);
    Optional<PaySlip> findByEmployeeIdAndMonthPeriodAndYearPeriod(Long employeeId, int month, int year);
    List<PaySlip> findByPublishedFalseAndMonthPeriodAndYearPeriod(int month, int year);
}
