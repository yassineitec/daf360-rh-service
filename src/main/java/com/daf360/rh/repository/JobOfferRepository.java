package com.daf360.rh.repository;

import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.domain.enums.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Offer rounds (V98). A candidate has one row PER NEGOTIATION ROUND, of which at most one is
 * current — the one with no {@code supersededAt}.
 *
 * <p>{@code findByCandidateId} returning an {@code Optional} is gone deliberately rather than
 * left to rot: with more than one round it throws {@code NonUniqueResultException}, and it was
 * the entry point of every read in {@code OfferService}. Callers now say which round they mean.
 */
@Repository
public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {

    /**
     * The candidate's current round — what the offer section shows and what renegotiation
     * supersedes. Empty for a candidate who has never been made an offer.
     */
    Optional<JobOffer> findFirstByCandidateIdAndSupersededAtIsNullOrderByRoundNumberDesc(Long candidateId);

    /** Every round, newest first — the negotiation history on the offer section. */
    List<JobOffer> findByCandidateIdOrderByRoundNumberDesc(Long candidateId);

    /**
     * The round the candidate accepted. This is the one that feeds the contract: it is
     * immutable once decided, unlike "the latest round", which a post-acceptance draft
     * would change.
     */
    Optional<JobOffer> findFirstByCandidateIdAndStatusOrderByDecidedAtDesc(Long candidateId, OfferStatus status);

    /**
     * Is a round still open — DRAFT (awaiting the finance decision) or SENT (with the
     * candidate)? The guard that stops a second offer being drafted while one is live.
     *
     * <p>Replaces {@code existsByCandidateId}, which asked "was there ever an offer" and,
     * once rounds exist, would refuse every renegotiation.
     */
    boolean existsByCandidateIdAndStatusIn(Long candidateId, List<OfferStatus> statuses);

    /** Highest round number so far — 0 rounds yields null, so the first round is 1. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT MAX(o.roundNumber) FROM JobOffer o WHERE o.candidateId = :candidateId")
    Integer findMaxRoundNumber(@org.springframework.data.repository.query.Param("candidateId") Long candidateId);
}
