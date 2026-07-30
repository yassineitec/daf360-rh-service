package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateCostApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CandidateCostApprovalRepository extends JpaRepository<CandidateCostApproval, Long> {

    List<CandidateCostApproval> findByPaysIdAndStatusOrderBySubmittedAtDesc(Long paysId, String status);

    List<CandidateCostApproval> findByCandidateIdOrderBySubmittedAtDesc(Long candidateId);

    /** Returns one row per candidate that has at least one approval record for the given pays. */
    @Query("""
            SELECT a.candidateId,
                   COUNT(a.id)      AS simulationCount,
                   MAX(a.submittedAt) AS latestSubmittedAt,
                   MAX(a.status)    AS latestStatus
            FROM CandidateCostApproval a
            WHERE a.paysId = :paysId
            GROUP BY a.candidateId
            ORDER BY MAX(a.submittedAt) DESC
            """)
    List<Object[]> findCandidateSummaryByPays(@Param("paysId") Long paysId);
}
