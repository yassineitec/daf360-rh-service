package com.daf360.rh.repository;

import com.daf360.rh.domain.NogLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface NogLevelRepository extends JpaRepository<NogLevel, Long> {

    List<NogLevel> findByPaysIdAndIsActiveTrueOrderByLevelOrderAsc(Long paysId);
    List<NogLevel> findByIsActiveTrueOrderByLevelOrderAsc();

    /** Several entities at once — see the note on GradeRepository. */
    List<NogLevel> findByPaysIdInAndIsActiveTrueOrderByLevelOrderAsc(Collection<Long> paysIds);
}
