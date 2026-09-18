package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.OfferStatus;
import com.daf360.rh.dto.offer.CreateOfferRequest;
import com.daf360.rh.dto.offer.OfferResponse;
import com.daf360.rh.dto.offer.RejectOfferRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.JobOfferRepository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Offer / salary-negotiation stage of recruitment.
 *
 * The offer is an additive gate between screening acceptance and IT provisioning:
 * {@code ACCEPTED → (send) OFFER_SENT → (accept) IT_IN_PROGRESS} or
 * {@code OFFER_SENT → (reject) REJECTED}. It deliberately does NOT alter the
 * existing provisioning row created at {@code acceptCandidate} time, so the IT
 * provisioning / onboarding chain stays untouched.
 *
 * <p><b>Rounds and the budget gate (V98).</b> Drafting and sending used to be one step, and
 * a renegotiation overwrote the single offer row in place. Now:
 *
 * <pre>
 *   draft  → a NEW round, status DRAFT, costed, submitted to the finance queue
 *   approve (finance, elsewhere) → the round becomes sendable
 *   send   → status SENT, candidate OFFER_SENT
 *   renegotiate → another DRAFT round, superseding the current one
 * </pre>
 *
 * Two consequences worth stating. No salary reaches a candidate without a recorded employer
 * cost and an explicit finance decision on that exact figure. And every round survives: the
 * negotiation history is the rows, not an audit-log line that only ever kept the last one.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OfferService {

    private final JobOfferRepository offerRepo;
    private final CandidateRepository candidateRepo;
    private final CandidateInterviewRepository interviewRepo;
    private final CandidateCostApprovalService costApprovalService;
    private final AuditService auditService;

    /** Statuses of a round that is still live — one of these blocks drafting another. */
    private static final List<OfferStatus> OPEN_STATUSES =
            List.of(OfferStatus.DRAFT, OfferStatus.SENT);

    /** The candidate's current round, whatever its state. 404 when none was ever drafted. */
    @Transactional(readOnly = true)
    public OfferResponse getByCandidate(Long candidateId) {
        return OfferResponse.from(findCurrentOrThrow(candidateId));
    }

    /** Every round, newest first — the negotiation history shown on the offer section. */
    @Transactional(readOnly = true)
    public List<OfferResponse> getRounds(Long candidateId) {
        return offerRepo.findByCandidateIdOrderByRoundNumberDesc(candidateId)
                .stream().map(OfferResponse::from).toList();
    }

    /**
     * Draft a round — the first offer, or a renegotiation that supersedes the current one.
     *
     * <p>Nothing reaches the candidate here. The round is created DRAFT and its simulation
     * goes to the finance approval queue in the same transaction, so a costed round and its
     * pending decision either both exist or neither does.
     *
     * <p>The entry gate depends on whether a negotiation is already under way:
     * a FIRST round needs the candidate ACCEPTED and past the interview gate; a LATER one
     * needs them at OFFER_SENT, i.e. a live offer to replace. Either way an open round
     * (DRAFT awaiting finance, or SENT and with the candidate) blocks a second.
     */
    public OfferResponse draftOffer(Long candidateId, CreateOfferRequest req, Long actorUserId) {
        Candidate candidate = findCandidateOrThrow(candidateId);

        if (req.getSimulationSnapshot() == null || req.getSimulationSnapshot().isBlank()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Une offre doit être chiffrée avant d'être proposée : lancez la simulation de coût.");
        }
        if (req.getProposedSalary() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le salaire proposé est obligatoire — c'est le montant soumis à la validation budgétaire.");
        }
        if (offerRepo.existsByCandidateIdAndStatusIn(candidateId, OPEN_STATUSES)) {
            throw new AppException(ErrorCode.OFFER_ALREADY_EXISTS,
                    "Une offre est déjà en cours pour ce candidat — elle doit être décidée "
                  + "ou renégociée avant d'en créer une autre.");
        }

        JobOffer previous = offerRepo
                .findFirstByCandidateIdAndSupersededAtIsNullOrderByRoundNumberDesc(candidateId)
                .orElse(null);

        if (previous == null) {
            if (candidate.getStatus() != CandidateStatus.ACCEPTED) {
                throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                        "Une offre ne peut être préparée que pour un candidat au statut ACCEPTED.");
            }
            requireApprovedInterviews(candidateId);
        } else if (candidate.getStatus() != CandidateStatus.OFFER_SENT) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Le candidat n'est pas au statut OFFER_SENT : la candidature est close.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Integer maxRound = offerRepo.findMaxRoundNumber(candidateId);
        int roundNumber = maxRound != null ? maxRound + 1 : 1;

        // The préavis is negotiated here, starting from the grade's default (V64). An
        // explicit value in the request always wins — including a deliberate 0. On a later
        // round an absent value carries the previous round's figure forward rather than
        // silently falling back to the grade default, which would undo a negotiated
        // derogation nobody meant to revisit.
        Integer noticeDays = req.getNoticePeriodDays() != null
                ? req.getNoticePeriodDays()
                : (previous != null ? previous.getNoticePeriodDays() : gradeNoticeDefault(candidate));

        JobOffer round = JobOffer.builder()
                .candidateId(candidateId)
                .roundNumber(roundNumber)
                .supersedesOfferId(previous != null ? previous.getId() : null)
                .askedSalary(req.getAskedSalary())
                .proposedSalary(req.getProposedSalary())
                .salaryNote(req.getSalaryNote())
                .noticePeriodDays(noticeDays)
                .noticePeriodNote(req.getNoticePeriodNote())
                .expectedHireDate(req.getExpectedHireDate())
                .expiryDate(req.getExpiryDate())
                .status(OfferStatus.DRAFT)
                .createdBy(actorUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        round = offerRepo.save(round);

        if (previous != null) {
            previous.setSupersededAt(now);
            previous.setUpdatedAt(now);
            offerRepo.save(previous);
        }

        // Same transaction: a DRAFT round with no pending decision would be invisible to
        // finance and unsendable forever.
        costApprovalService.submitForOffer(round, candidate, req.getSimulationSnapshot(),
                req.getFiscalYear(), actorUserId);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "DRAFT_OFFER", "CANDIDATE", candidateId,
                previous != null
                        ? "round=" + previous.getRoundNumber() + "; proposedSalary=" + previous.getProposedSalary()
                        : null,
                "round=" + roundNumber + "; proposedSalary=" + round.getProposedSalary()
                        + "; noticePeriodDays=" + noticeDays + "; status=DRAFT");

        return OfferResponse.from(round);
    }

    /**
     * Extend an approved round to the candidate → offer SENT, candidate OFFER_SENT.
     *
     * <p>The budget gate: refused unless finance has APPROVED this exact round. Approval is
     * per round, so a figure that cleared review cannot be edited and sent — a revision is a
     * new round, which goes back through the queue.
     */
    public OfferResponse sendOffer(Long candidateId, Long actorUserId) {
        Candidate candidate = findCandidateOrThrow(candidateId);
        JobOffer round = findCurrentOrThrow(candidateId);

        if (round.getStatus() != OfferStatus.DRAFT) {
            throw new AppException(ErrorCode.OFFER_STATUS_INVALID,
                    "Seule une offre au statut DRAFT peut être envoyée au candidat.");
        }
        if (!costApprovalService.isOfferApproved(round.getId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Cette offre n'a pas encore été validée par le contrôle budgétaire.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        round.setStatus(OfferStatus.SENT);
        round.setSentAt(now);
        round.setUpdatedAt(now);
        offerRepo.save(round);

        CandidateStatus before = candidate.getStatus();
        candidate.setStatus(CandidateStatus.OFFER_SENT);
        candidate.setUpdatedAt(now);
        candidateRepo.save(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "SEND_OFFER", "CANDIDATE", candidateId,
                "status=" + before,
                "status=OFFER_SENT; round=" + round.getRoundNumber()
                        + "; proposedSalary=" + round.getProposedSalary());

        return OfferResponse.from(round);
    }

    /** Candidate accepts the offer → offer ACCEPTED, candidate enters IT provisioning. */
    public OfferResponse acceptOffer(Long candidateId, Long actorUserId) {
        JobOffer offer = findCurrentOrThrow(candidateId);
        Candidate candidate = findCandidateOrThrow(candidateId);
        assertPending(offer, candidate);

        OffsetDateTime now = OffsetDateTime.now();
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setDecidedAt(now);
        offer.setUpdatedAt(now);
        offerRepo.save(offer);

        // Offer accepted → begin IT provisioning (the ItProvisioning task already
        // exists from acceptCandidate; here we only advance the candidate status).
        candidate.setStatus(CandidateStatus.IT_IN_PROGRESS);
        candidate.setUpdatedAt(now);
        candidateRepo.save(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "ACCEPT_OFFER", "CANDIDATE", candidateId,
                "status=OFFER_SENT", "status=IT_IN_PROGRESS");

        return OfferResponse.from(offer);
    }

    /** Candidate declines (or RH withdraws) the offer → offer REJECTED, candidate REJECTED. */
    public OfferResponse rejectOffer(Long candidateId, RejectOfferRequest req, Long actorUserId) {
        JobOffer offer = findCurrentOrThrow(candidateId);
        Candidate candidate = findCandidateOrThrow(candidateId);
        assertPending(offer, candidate);

        OffsetDateTime now = OffsetDateTime.now();
        offer.setStatus(OfferStatus.REJECTED);
        offer.setRejectionReason(req.getRejectionReason());
        offer.setDecidedAt(now);
        offer.setUpdatedAt(now);
        offerRepo.save(offer);

        candidate.setStatus(CandidateStatus.REJECTED);
        candidate.setRejectionReason(req.getRejectionReason());
        candidate.setUpdatedAt(now);
        candidateRepo.save(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "REJECT_OFFER", "CANDIDATE", candidateId,
                "status=OFFER_SENT", "status=REJECTED; reason=" + req.getRejectionReason());

        return OfferResponse.from(offer);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The candidate's grade default préavis (V64), or null when the grade is unset or has
     * no default.
     *
     * Null is returned rather than a constant on purpose: a fabricated default would put a
     * figure in an offer document that nobody decided. The offer form shows it as unset and
     * makes RH type one.
     */
    private Integer gradeNoticeDefault(Candidate candidate) {
        try {
            return candidate.getAppliedGrade() != null
                    ? candidate.getAppliedGrade().getNoticePeriodDays() : null;
        } catch (Exception ex) {
            // Lazy association on a detached candidate — not worth failing the offer over.
            log.debug("Could not read the grade préavis default for candidate {}: {}",
                    candidate.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * Interview gate: an offer requires the candidate to have passed the interview
     * stage — at least one interview with result PASS and none with result FAIL.
     */
    private void requireApprovedInterviews(Long candidateId) {
        List<CandidateInterview> interviews =
                interviewRepo.findByCandidateIdOrderBySequenceNumber(candidateId);
        boolean anyPass = interviews.stream().anyMatch(i -> i.getResult() == InterviewResult.PASS);
        boolean anyFail = interviews.stream().anyMatch(i -> i.getResult() == InterviewResult.FAIL);
        if (!anyPass || anyFail) {
            throw new AppException(ErrorCode.OFFER_INTERVIEW_REQUIRED);
        }
    }

    private void assertPending(JobOffer offer, Candidate candidate) {
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new AppException(ErrorCode.OFFER_STATUS_INVALID,
                    "Seule une offre au statut SENT peut être acceptée ou refusée.");
        }
        if (candidate.getStatus() != CandidateStatus.OFFER_SENT) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Le candidat n'est pas au statut OFFER_SENT.");
        }
    }

    /**
     * The candidate's current round — the one no later round has superseded.
     *
     * <p>Replaces a {@code findByCandidateId} returning an Optional, which since V98 throws
     * {@code NonUniqueResultException} the moment a candidate has been renegotiated: there
     * is a row per round now, and "the offer" has to name which one.
     */
    private JobOffer findCurrentOrThrow(Long candidateId) {
        return offerRepo.findFirstByCandidateIdAndSupersededAtIsNullOrderByRoundNumberDesc(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.OFFER_NOT_FOUND));
    }

    private Candidate findCandidateOrThrow(Long candidateId) {
        return candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
    }
}
