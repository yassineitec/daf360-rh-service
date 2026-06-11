package com.daf360.rh.repository;

import com.daf360.rh.domain.Grade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {

    List<Grade> findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(Long paysId);
}
