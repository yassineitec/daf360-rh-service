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
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OfferService {

    private final JobOfferRepository offerRepo;
    private final CandidateRepository candidateRepo;
    private final CandidateInterviewRepository interviewRepo;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public OfferResponse getByCandidate(Long candidateId) {
        return OfferResponse.from(findOfferOrThrow(candidateId));
    }

    /** Extend an offer to an ACCEPTED candidate → status OFFER_SENT. */
    public OfferResponse sendOffer(Long candidateId, CreateOfferRequest req, Long actorUserId) {
        Candidate candidate = findCandidateOrThrow(candidateId);

        if (candidate.getStatus() != CandidateStatus.ACCEPTED) {
            throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                    "Une offre ne peut être envoyée qu'à un candidat au statut ACCEPTED.");
        }
        if (offerRepo.existsByCandidateId(candidateId)) {
            throw new AppException(ErrorCode.OFFER_ALREADY_EXISTS);
        }
        requireApprovedInterviews(candidateId);

        OffsetDateTime now = OffsetDateTime.now();
        JobOffer offer = JobOffer.builder()
                .candidateId(candidateId)
                .askedSalary(req.getAskedSalary())
                .proposedSalary(req.getProposedSalary())
                .salaryNote(req.getSalaryNote())
                .expectedHireDate(req.getExpectedHireDate())
                .expiryDate(req.getExpiryDate())
                .sentAt(now)
                .status(OfferStatus.SENT)
                .createdBy(actorUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        offer = offerRepo.save(offer);

        candidate.setStatus(CandidateStatus.OFFER_SENT);
        candidate.setUpdatedAt(now);
        candidateRepo.save(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "SEND_OFFER", "CANDIDATE", candidateId,
                "status=ACCEPTED", "status=OFFER_SENT; proposedSalary=" + req.getProposedSalary());

        return OfferResponse.from(offer);
    }

    /**
     * Renegotiate a still-open offer — revise the proposed salary / terms and keep
     * it SENT. Allowed only while the candidate is OFFER_SENT (i.e. the candidature
     * is not closed: not accepted, hired or rejected).
     */
    public OfferResponse renegotiateOffer(Long candidateId, CreateOfferRequest req, Long actorUserId) {
        JobOffer offer = findOfferOrThrow(candidateId);
        Candidate candidate = findCandidateOrThrow(candidateId);
        assertPending(offer, candidate); // SENT + candidate OFFER_SENT

        OffsetDateTime now = OffsetDateTime.now();
        String before = "proposedSalary=" + offer.getProposedSalary();
        if (req.getAskedSalary() != null)      offer.setAskedSalary(req.getAskedSalary());
        if (req.getProposedSalary() != null)   offer.setProposedSalary(req.getProposedSalary());
        if (req.getSalaryNote() != null)        offer.setSalaryNote(req.getSalaryNote());
        if (req.getExpectedHireDate() != null)  offer.setExpectedHireDate(req.getExpectedHireDate());
        if (req.getExpiryDate() != null)        offer.setExpiryDate(req.getExpiryDate());
        offer.setSentAt(now);        // re-issued
        offer.setStatus(OfferStatus.SENT);
        offer.setUpdatedAt(now);
        offerRepo.save(offer);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "RENEGOTIATE_OFFER", "CANDIDATE", candidateId,
                before, "proposedSalary=" + offer.getProposedSalary());

        return OfferResponse.from(offer);
    }

    /** Candidate accepts the offer → offer ACCEPTED, candidate enters IT provisioning. */
    public OfferResponse acceptOffer(Long candidateId, Long actorUserId) {
        JobOffer offer = findOfferOrThrow(candidateId);
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
        JobOffer offer = findOfferOrThrow(candidateId);
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

    private JobOffer findOfferOrThrow(Long candidateId) {
        return offerRepo.findByCandidateId(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.OFFER_NOT_FOUND));
    }

    private Candidate findCandidateOrThrow(Long candidateId) {
        return candidateRepo.findById(candidateId)
                .orElseThrow(() -> new AppException(ErrorCode.CANDIDATE_NOT_FOUND));
    }
}
