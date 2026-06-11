package com.daf360.rh.repository;

import com.daf360.rh.domain.BreakLegalRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface BreakLegalRuleRepository extends JpaRepository<BreakLegalRule, Long> {

    List<BreakLegalRule> findByPaysIdAndIsActiveTrueOrderByMinWorkHoursAsc(Long paysId);

    @Query("""
        SELECT r FROM BreakLegalRule r
        WHERE r.paysId = :paysId
          AND r.isActive = true
          AND r.effectiveFrom <= :workDate
          AND (r.effectiveTo IS NULL OR r.effectiveTo >= :workDate)
          AND r.minWorkHours <= :grossHours
        ORDER BY r.minWorkHours ASC
        """)
    List<BreakLegalRule> findApplicableRules(
            @Param("paysId") Long paysId,
            @Param("grossHours") BigDecimal grossHours,
            @Param("workDate") LocalDate workDate);
}
