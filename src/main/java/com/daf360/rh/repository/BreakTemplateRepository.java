package com.daf360.rh.repository;

import com.daf360.rh.domain.BreakTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BreakTemplateRepository extends JpaRepository<BreakTemplate, Long> {

    List<BreakTemplate> findByRegimeIdAndIsActiveTrueOrderBySortOrderAsc(Long regimeId);

    List<BreakTemplate> findByPaysIdAndIsActiveTrueOrderBySortOrderAsc(Long paysId);

    @Query("""
        SELECT COUNT(r) > 0 FROM WorkingTimeRegime r
        WHERE r.id = :regimeId AND r.paysId = :paysId
        """)
    boolean regimeBelongsToPays(@Param("regimeId") Long regimeId, @Param("paysId") Long paysId);
}
