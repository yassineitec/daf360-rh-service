package com.daf360.rh.repository;

import com.daf360.rh.domain.Grade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {

    List<Grade> findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(Long paysId);
    List<Grade> findByIsActiveTrueOrderBySortOrderAsc();

    /**
     * Several entities at once — a role in V74 LIST mode covers more than one pays, and the
     * single-id finder above keeps only one of them. `Collection`, not `List`: the caller
     * holds its scope as a Set.
     */
    List<Grade> findByPaysIdInAndIsActiveTrueOrderBySortOrderAsc(Collection<Long> paysIds);
}
