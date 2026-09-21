package com.daf360.rh.repository;

import com.daf360.rh.domain.Discipline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface DisciplineRepository extends JpaRepository<Discipline, Long> {

    List<Discipline> findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(Long paysId);
    List<Discipline> findByIsActiveTrueOrderBySortOrderAsc();

    /** Several entities at once — see the note on GradeRepository. */
    List<Discipline> findByPaysIdInAndIsActiveTrueOrderBySortOrderAsc(Collection<Long> paysIds);
}
