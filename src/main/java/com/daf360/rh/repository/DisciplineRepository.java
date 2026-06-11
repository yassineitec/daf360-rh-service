package com.daf360.rh.repository;

import com.daf360.rh.domain.Discipline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisciplineRepository extends JpaRepository<Discipline, Long> {

    List<Discipline> findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(Long paysId);
}
