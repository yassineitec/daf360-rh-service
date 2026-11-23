package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.OfferStatus;
import com.daf360.rh.dto.email.CandidateEmailDtos.EmailPreviewDto;
import com.daf360.rh.dto.hiring.CandidateCostApprovalDto;
import com.daf360.rh.dto.offer.CreateOfferRequest;
import com.daf360.rh.dto.offer.OfferResponse;
import com.daf360.rh.dto.offer.RejectOfferRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.OfferNotifier;
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
    private final OfferNotifier offerNotifier;
    private final HiringHandoffService handoff;

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
        JobOffer previous = offerRepo
                .findFirstByCandidateIdAndSupersededAtIsNullOrderByRoundNumberDesc(candidateId)
                .orElse(null);

        // The candidate's counter (V133) answers an offer they HAVE: one that is with them.
        boolean candidateCounter = Boolean.TRUE.equals(req.getCandidateCounter());
        if (candidateCounter && (previous == null
                || (previous.getStatus() != OfferStatus.SENT && previous.getStatus() != OfferStatus.EXPIRED))) {
            throw new AppException(ErrorCode.OFFER_STATUS_INVALID,
                    "Une contre-proposition du candidat répond à une offre qui lui a été envoyée.");
        }

        /*
         * A renegotiation REPLACES the live round — so the live round being DRAFT or SENT is
         * the normal case, not a conflict. The old guard refused any draft while a DRAFT/SENT
         * round existed, which made « Réviser » (after a finance refusal) and « Renégocier »
         * (offer with the candidate) fail every time: there is always such a round then.
         *
         *   no round        → first offer: candidate ACCEPTED + interviews passed
         *   DRAFT           → revision (finance refused, or RH corrects before the answer):
         *                     candidate ACCEPTED (first round) or OFFER_SENT (later round)
         *   SENT / EXPIRED  → counter-proposal while the offer is with the candidate: OFFER_SENT
         *   ACCEPTED / REJECTED → decided, the negotiation is over
         */
        if (previous == null) {
            if (candidate.getStatus() != CandidateStatus.ACCEPTED) {
                throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                        "Une offre ne peut être préparée que pour un candidat au statut ACCEPTED.");
            }
            requireApprovedInterviews(candidateId);
        } else if (previous.getStatus() == OfferStatus.ACCEPTED || previous.getStatus() == OfferStatus.REJECTED) {
            throw new AppException(ErrorCode.OFFER_STATUS_INVALID,
                    "L'offre a déjà été " + (previous.getStatus() == OfferStatus.ACCEPTED ? "acceptée" : "refusée")
                  + " par le candidat : la négociation est close.");
        } else if (previous.getStatus() == OfferStatus.DRAFT) {
            if (candidate.getStatus() != CandidateStatus.ACCEPTED
                    && candidate.getStatus() != CandidateStatus.OFFER_SENT) {
                throw new AppException(ErrorCode.CANDIDATE_STATUS_INVALID,
                        "La candidature n'est plus à l'étape de l'offre.");
            }
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
                // A candidate's counter carries THEIR figure: what they ask is what is proposed.
                .askedSalary(candidateCounter ? req.getProposedSalary() : req.getAskedSalary())
                .proposedSalary(req.getProposedSalary())
                .proposedBy(candidateCounter ? JobOffer.PROPOSED_BY_CANDIDATE : JobOffer.PROPOSED_BY_RH)
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
            // A DRAFT replaced before finance answered: its request leaves the queue, or
            // finance would be asked to decide on a figure nobody is offering any more.
            if (previous.getStatus() == OfferStatus.DRAFT) {
                costApprovalService.withdrawPendingForOffer(previous.getId());
            }
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
                        + "; proposedBy=" + round.getProposedBy()
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
        return sendOffer(candidateId, actorUserId, null, null);
    }

    /**
     * The email RH is about to send for the current round (V134) — the entity's template,
     * rendered. Same gates as the send itself, so the preview never shows an email that
     * could not go.
     */
    @Transactional(readOnly = true)
    public EmailPreviewDto previewOfferEmail(Long candidateId) {
        Candidate candidate = findCandidateOrThrow(candidateId);
        JobOffer round = findCurrentOrThrow(candidateId);
        assertSendable(candidate, round);
        return offerNotifier.preview(candidate, round,
                OfferNotifier.currencyOf(costApprovalService.snapshotForOffer(round.getId())));
    }

    /**
     * Sends the approved round, with RH's edited subject and body when given (V134) — the
     * modal shows the rendered template and RH may change it for this one send.
     */
    public OfferResponse sendOffer(Long candidateId, Long actorUserId, String emailSubject, String emailBody) {
        Candidate candidate = findCandidateOrThrow(candidateId);
        JobOffer round = findCurrentOrThrow(candidateId);
        assertSendable(candidate, round);

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

        offerNotifier.offerToCandidate(candidate, round,
                OfferNotifier.currencyOf(costApprovalService.snapshotForOffer(round.getId())),
                emailSubject, emailBody);

        return OfferResponse.from(round);
    }

    /**
     * An approved DRAFT round — RH's or the DAF's own — to a candidate who can receive it.
     * No address, no send: the offer would be marked « envoyée » while nobody received it.
     */
    private void assertSendable(Candidate candidate, JobOffer round) {
        if (round.getStatus() != OfferStatus.DRAFT) {
            throw new AppException(ErrorCode.OFFER_STATUS_INVALID,
                    "Seule une offre au statut DRAFT peut être envoyée au candidat.");
        }
        if (!costApprovalService.isOfferApproved(round.getId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Cette offre n'a pas encore été validée par le contrôle budgétaire.");
        }
        if (candidate.getEmailPersonal() == null || candidate.getEmailPersonal().isBlank()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le candidat n'a pas d'email personnel : renseignez-le dans l'onglet Profil avant d'envoyer l'offre.");
        }
    }

    // ── The DAF's decision (finance approval queue) ──────────────────────────

    /**
     * The DAF decides on a request of its queue. The negotiation goes both ways (V133):
     *
     * <pre>
     *   approve an RH round          → RH sends it (unchanged)
     *   approve a CANDIDATE round    → both parties agree on the figure: offer ACCEPTED,
     *                                  the offer step is over
     *   refuse, no figure            → RH revises (unchanged)
     *   refuse WITH a figure         → that figure is the DAF's own offer: a new round, already
     *                                  approved (DRAFT); RH sends it — previewing and possibly
     *                                  editing the email (V134) — and the candidate accepts,
     *                                  refuses or counters
     * </pre>
     *
     * A request that gates no round (a legacy pre-validation) is decided as before.
     */
    public CandidateCostApprovalDto decideByFinance(Long approvalId, boolean approve, String notes,
                                                   java.math.BigDecimal contreProp, String contrePropSnapshot,
                                                   Long actorUserId) {
        CandidateCostApprovalDto decision = approve
                ? costApprovalService.approve(approvalId, notes, actorUserId)
                : costApprovalService.reject(approvalId, notes, contreProp, actorUserId);
        applyDecisionToRound(decision, approve, notes, contreProp, contrePropSnapshot, actorUserId);
        return decision;
    }

    private void applyDecisionToRound(CandidateCostApprovalDto decision, boolean approve, String notes,
                                      java.math.BigDecimal contreProp, String contrePropSnapshot,
                                      Long actorUserId) {
        if (decision.getJobOfferId() == null) return;

        JobOffer round = offerRepo.findById(decision.getJobOfferId()).orElse(null);
        // Only the live DRAFT round moves; a superseded one keeps its decision as history.
        if (round == null || round.getSupersededAt() != null || round.getStatus() != OfferStatus.DRAFT) return;
        Candidate candidate = findCandidateOrThrow(round.getCandidateId());
        String currency = OfferNotifier.currencyOf(decision.getSimulationSnapshot());

        if (approve) {
            if (JobOffer.PROPOSED_BY_CANDIDATE.equals(round.getProposedBy())) {
                concludeAgreement(round, candidate, actorUserId);
                offerNotifier.agreed(round.getCreatedBy(), candidate, round, currency);
            } else {
                offerNotifier.approvedToSend(round.getCreatedBy(), candidate, round);
            }
            return;
        }
        if (contreProp == null) {
            offerNotifier.refused(round.getCreatedBy(), candidate, round, notes);
            return;
        }
        if (contrePropSnapshot == null || contrePropSnapshot.isBlank()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La contre-proposition doit être chiffrée : lancez la simulation dans la fenêtre de décision.");
        }
        JobOffer counter = recordFinanceCounter(round, candidate, contreProp, contrePropSnapshot,
                decision.getFiscalYear(), notes, actorUserId);
        String counterCurrency = OfferNotifier.currencyOf(contrePropSnapshot);
        offerNotifier.financeCounterReady(round.getCreatedBy(), candidate, counter,
                counterCurrency != null ? counterCurrency : currency);
    }

    /**
     * The DAF's figure becomes the next round — approved by its author, DRAFT until RH sends
     * it. The candidate's status does not move: nothing has reached them yet.
     */
    private JobOffer recordFinanceCounter(JobOffer refused, Candidate candidate, java.math.BigDecimal salary,
                                          String snapshot, Integer fiscalYear, String notes, Long actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        Integer maxRound = offerRepo.findMaxRoundNumber(candidate.getId());
        JobOffer counter = JobOffer.builder()
                .candidateId(candidate.getId())
                .roundNumber(maxRound != null ? maxRound + 1 : refused.getRoundNumber() + 1)
                .supersedesOfferId(refused.getId())
                .askedSalary(refused.getAskedSalary())
                .proposedSalary(salary)
                .proposedBy(JobOffer.PROPOSED_BY_DAF)
                .salaryNote(refused.getSalaryNote())
                .noticePeriodDays(refused.getNoticePeriodDays())
                .noticePeriodNote(refused.getNoticePeriodNote())
                .expectedHireDate(refused.getExpectedHireDate())
                .expiryDate(refused.getExpiryDate())
                .status(OfferStatus.DRAFT)
                .createdBy(actorUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        counter = offerRepo.save(counter);

        refused.setSupersededAt(now);
        refused.setUpdatedAt(now);
        offerRepo.save(refused);

        costApprovalService.recordFinanceCounter(counter, candidate, snapshot, fiscalYear, notes, actorUserId);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "FINANCE_COUNTER_OFFER", "CANDIDATE", candidate.getId(),
                "round=" + refused.getRoundNumber() + "; proposedSalary=" + refused.getProposedSalary(),
                "round=" + counter.getRoundNumber() + "; proposedSalary=" + salary
                        + "; proposedBy=DAF; status=DRAFT (approved, to send)");
        return counter;
    }

    /**
     * The DAF approved the candidate's own figure: both parties agree. The round is closed
     * ACCEPTED and the candidature leaves the offer step — exactly as when the candidate
     * accepts an offer that was sent to them.
     */
    private void concludeAgreement(JobOffer round, Candidate candidate, Long actorUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        round.setStatus(OfferStatus.ACCEPTED);
        round.setDecidedAt(now);
        round.setUpdatedAt(now);
        offerRepo.save(round);

        CandidateStatus before = candidate.getStatus();
        // Out of the candidate workflow: onboarding and IT provisioning open together.
        handoff.startOnboardingAndIt(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "OFFER_AGREED", "CANDIDATE", candidate.getId(),
                "status=" + before, "status=HR_IN_PROGRESS (onboarding + IT); round=" + round.getRoundNumber()
                        + "; proposedSalary=" + round.getProposedSalary() + "; proposedBy=CANDIDATE");
    }

    /**
     * Candidate accepts the offer → offer ACCEPTED; the candidature leaves the candidate
     * workflow and onboarding + IT provisioning start in parallel.
     */
    public OfferResponse acceptOffer(Long candidateId, Long actorUserId) {
        JobOffer offer = findCurrentOrThrow(candidateId);
        Candidate candidate = findCandidateOrThrow(candidateId);
        assertPending(offer, candidate);

        OffsetDateTime now = OffsetDateTime.now();
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setDecidedAt(now);
        offer.setUpdatedAt(now);
        offerRepo.save(offer);

        // Both parties agree: onboarding and IT provisioning open together.
        handoff.startOnboardingAndIt(candidate);

        auditService.log(actorUserId != null ? actorUserId.toString() : "SYSTEM",
                "ACCEPT_OFFER", "CANDIDATE", candidateId,
                "status=OFFER_SENT", "status=HR_IN_PROGRESS (onboarding + IT)");

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
