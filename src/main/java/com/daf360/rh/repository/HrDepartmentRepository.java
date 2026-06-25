package com.daf360.rh.repository;

import com.daf360.rh.domain.HrDepartment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HrDepartmentRepository extends JpaRepository<HrDepartment, Long> {

    List<HrDepartment> findByPaysIdAndIsActiveTrueOrderByLabelFrAsc(Long paysId);
    List<HrDepartment> findByIsActiveTrueOrderByLabelFrAsc();

    List<HrDepartment> findByPaysIdAndParentIdIsNullAndIsActiveTrue(Long paysId);
}
