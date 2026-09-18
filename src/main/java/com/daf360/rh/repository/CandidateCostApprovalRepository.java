package com.daf360.rh.repository;

import com.daf360.rh.domain.CandidateCostApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CandidateCostApprovalRepository extends JpaRepository<CandidateCostApproval, Long> {

    List<CandidateCostApproval> findByPaysIdAndStatusOrderBySubmittedAtDesc(Long paysId, String status);

    List<CandidateCostApproval> findByCandidateIdOrderBySubmittedAtDesc(Long candidateId);

    /**
     * Has this offer round cleared the budget gate? Read by {@code OfferService.sendOffer}.
     *
     * <p>Per round rather than per candidate: an approval belongs to the figure it reviewed,
     * so a revised salary is a new round that goes back through the queue instead of
     * inheriting the previous decision.
     */
    boolean existsByJobOfferIdAndStatus(Long jobOfferId, String status);

    /** Every decision on one round, newest first — the offer section's approval trail. */
    List<CandidateCostApproval> findByJobOfferIdOrderBySubmittedAtDesc(Long jobOfferId);

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
