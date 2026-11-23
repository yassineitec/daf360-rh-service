package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateCostApproval;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.lifecycle.ContractTypeBridge;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.dto.hiring.CandidateCostApprovalDto;
import com.daf360.rh.dto.hiring.CandidateSimulationSummaryDto;
import com.daf360.rh.dto.hiring.SubmitCostApprovalRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.CandidateCostApprovalRepository;
import com.daf360.rh.repository.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class CandidateCostApprovalService {

    private final CandidateCostApprovalRepository approvalRepo;
    private final CandidateRepository             candidateRepo;
    /** Resolves the candidate's CONTRACT_TYPE into the contract code the snapshot was run for. */
    private final ContractTypeBridge              contractTypeBridge;
    private final ContractTypeRefs                contractTypeRefs;

    /**
     * The approval that gates one offer round (V98) — created with the round itself, in the
     * same transaction, by {@link OfferService#draftOffer}.
     *
     * <p>Distinct from {@link #submit}, which is the standalone budget pre-validation RH runs
     * from the creation wizard or the Rémunération tab: that one carries no
     * {@code jobOfferId} and gates nothing. Both land in the same finance queue.
     *
     * <p>{@code proposedSalary} is the round's own figure and is stored alongside
     * {@code salaireNetRh}: the two diverge as soon as finance counter-proposes, and the
     * record has to say which number was actually put in front of the approver.
     */
    public CandidateCostApprovalDto submitForOffer(JobOffer offer, Candidate candidate,
                                                   String simulationSnapshot, Integer fiscalYear,
                                                   Long submittedBy) {
        CandidateCostApproval approval = CandidateCostApproval.builder()
                .candidateId(candidate.getId())
                .jobOfferId(offer.getId())
                .paysId(candidate.getPaysId())
                .fiscalYear(fiscalYear != null ? fiscalYear : java.time.Year.now().getValue())
                // The budget line stays the candidate's recorded figure; the offer's own
                // number is proposedSalary. NOT NULL in the schema, so it falls back to the
                // offer when RH never set a separate budget.
                .salaireNetRh(candidate.getSalaireNetRh() != null
                        ? candidate.getSalaireNetRh() : offer.getProposedSalary())
                .salaireNetCandidat(candidate.getSalaireNetCandidat())
                .proposedSalary(offer.getProposedSalary())
                .contractTypeId(storedContractType(candidate, null, candidate.getPaysId()))
                .simulationSnapshot(simulationSnapshot)
                .status("PENDING")
                .submittedBy(submittedBy)
                .submittedAt(OffsetDateTime.now())
                .build();
        return toDto(approvalRepo.save(approval), candidate);
    }

    /**
     * The decision record of a round the DAF proposed itself (V133): its counter-proposal,
     * costed in the decision modal. It is the DAF's own figure, so it is recorded APPROVED by
     * the same person at the same instant — never queued for a decision nobody else makes.
     */
    public CandidateCostApprovalDto recordFinanceCounter(JobOffer offer, Candidate candidate,
                                                         String simulationSnapshot, Integer fiscalYear,
                                                         String notes, Long financeUserId) {
        OffsetDateTime now = OffsetDateTime.now();
        CandidateCostApproval approval = CandidateCostApproval.builder()
                .candidateId(candidate.getId())
                .jobOfferId(offer.getId())
                .paysId(candidate.getPaysId())
                .fiscalYear(fiscalYear != null ? fiscalYear : java.time.Year.now().getValue())
                .salaireNetRh(offer.getProposedSalary())
                .salaireNetCandidat(candidate.getSalaireNetCandidat())
                .proposedSalary(offer.getProposedSalary())
                .contractTypeId(storedContractType(candidate, null, candidate.getPaysId()))
                .simulationSnapshot(simulationSnapshot)
                .status("APPROVED")
                .submittedBy(financeUserId)
                .submittedAt(now)
                .approvedBy(financeUserId)
                .approvedAt(now)
                .approvalNotes(notes)
                .build();
        return toDto(approvalRepo.save(approval), candidate);
    }

    /** The costing stored with a round — the latest decision record's snapshot, or null. */
    @Transactional(readOnly = true)
    public String snapshotForOffer(Long jobOfferId) {
        return approvalRepo.findByJobOfferIdOrderBySubmittedAtDesc(jobOfferId).stream()
                .map(CandidateCostApproval::getSimulationSnapshot)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
    }

    /**
     * Has finance approved this exact round? The gate {@link OfferService#sendOffer} enforces.
     *
     * <p>Per round, deliberately: an approval belongs to the figure it reviewed, so revising
     * a salary after sign-off produces a new round that must be approved again rather than
     * inheriting the old decision.
     */
    @Transactional(readOnly = true)
    public boolean isOfferApproved(Long jobOfferId) {
        return approvalRepo.existsByJobOfferIdAndStatus(jobOfferId, "APPROVED");
    }

    /**
     * A DRAFT round was replaced by a new one before finance decided on it: its PENDING
     * request is removed from the queue (it never got a decision, so no history is lost).
     * Decided requests (APPROVED / REJECTED) stay as the record of that round.
     */
    public void withdrawPendingForOffer(Long jobOfferId) {
        approvalRepo.findByJobOfferIdOrderBySubmittedAtDesc(jobOfferId).stream()
                .filter(a -> "PENDING".equals(a.getStatus()))
                .forEach(approvalRepo::delete);
    }

    /**
     * The CONTRACT_TYPE list id to record: the candidate's own type (employment_type_id) when
     * set — the exact type being approved, « contrat » included — else the code the client
     * sent (a payroll code such as CDI) resolved in the list; kept as is if unknown.
     */
    private String storedContractType(Candidate candidate, String requestedCode, Long paysId) {
        String ref = candidate != null && candidate.getEmploymentTypeId() != null
                ? contractTypeBridge.resolveContractTypeRef(candidate.getEmploymentTypeId())
                : (requestedCode != null && !requestedCode.isBlank() ? requestedCode : ContractTypeRefs.DEFAULT_NATURE);
        Long id = contractTypeRefs.idOf(ref, paysId);
        return id != null ? String.valueOf(id) : ref;
    }

    /**
     * Standalone budget pre-validation (creation wizard, candidate Rémunération tab).
     *
     * <p>One request per candidate in finance's queue: while a pre-validation is still
     * PENDING, submitting again <b>updates that request</b> (new figures, snapshot, author,
     * date) instead of inserting another row. Before, every click on « Soumettre » added a
     * line to finance's queue for the same candidate (test campaign TC-01.06). Once finance
     * has decided (APPROVED / REJECTED), a new submission is a new request, so the decision
     * history is kept. Offer rounds ({@link #submitForOffer}) are not affected.
     */
    public CandidateCostApprovalDto submit(SubmitCostApprovalRequest req, Long submittedBy) {
        Candidate candidate = candidateRepo.findById(req.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Candidat introuvable : id=" + req.getCandidateId()));

        CandidateCostApproval approval = approvalRepo
                .findByCandidateIdAndJobOfferIdIsNullAndStatusOrderBySubmittedAtDesc(req.getCandidateId(), "PENDING")
                .stream().findFirst()
                .orElseGet(() -> CandidateCostApproval.builder()
                        .candidateId(req.getCandidateId())
                        .status("PENDING")
                        .build());

        approval.setPaysId(req.getPaysId());
        approval.setFiscalYear(req.getFiscalYear());
        approval.setSalaireNetRh(req.getSalaireNetRh());
        approval.setSalaireNetCandidat(req.getSalaireNetCandidat());
        approval.setContractTypeId(storedContractType(candidate, req.getContractTypeCode(), req.getPaysId()));
        approval.setSimulationSnapshot(req.getSimulationSnapshot());
        approval.setSubmittedBy(submittedBy);
        approval.setSubmittedAt(OffsetDateTime.now());

        return toDto(approvalRepo.save(approval), candidate);
    }

    @Transactional(readOnly = true)
    public List<CandidateCostApprovalDto> getPendingByPays(Long paysId) {
        return approvalRepo.findByPaysIdAndStatusOrderBySubmittedAtDesc(paysId, "PENDING")
                .stream()
                .map(a -> toDto(a, candidateRepo.findById(a.getCandidateId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CandidateCostApprovalDto> getByCandidate(Long candidateId) {
        Candidate candidate = candidateRepo.findById(candidateId).orElse(null);
        return approvalRepo.findByCandidateIdOrderBySubmittedAtDesc(candidateId)
                .stream()
                .map(a -> toDto(a, candidate))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CandidateSimulationSummaryDto> getCandidatesWithHistory(Long paysId) {
        List<Object[]> rows = approvalRepo.findCandidateSummaryByPays(paysId);
        return rows.stream().map(row -> {
            Long candidateId        = (Long)            row[0];
            long simulationCount    = ((Number)         row[1]).longValue();
            java.time.OffsetDateTime latestAt = (java.time.OffsetDateTime) row[2];
            String latestStatus     = (String)          row[3];

            CandidateSimulationSummaryDto dto = new CandidateSimulationSummaryDto();
            dto.setCandidateId(candidateId);
            dto.setSimulationCount(simulationCount);
            dto.setLatestSubmittedAt(latestAt);
            dto.setLatestStatus(latestStatus);

            candidateRepo.findById(candidateId).ifPresent(c -> {
                dto.setFirstName(c.getFirstName());
                dto.setLastName(c.getLastName());
                dto.setAppliedPosition(c.getAppliedPosition());
                dto.setCandidateLocation(c.getLocation());
                dto.setPaysId(c.getPaysId());
                // Les deux nets de la négociation — cartes de /payroll/candidate-simulation.
                dto.setSalaireNetRh(c.getSalaireNetRh());
                dto.setSalaireNetCandidat(c.getSalaireNetCandidat());
            });
            return dto;
        }).toList();
    }

    public CandidateCostApprovalDto approve(Long id, String notes, Long approvedBy) {
        CandidateCostApproval approval = findPending(id);
        approval.setStatus("APPROVED");
        approval.setApprovedBy(approvedBy);
        approval.setApprovedAt(OffsetDateTime.now());
        approval.setApprovalNotes(notes);
        CandidateCostApproval saved = approvalRepo.save(approval);
        return toDto(saved, candidateRepo.findById(saved.getCandidateId()).orElse(null));
    }

    /**
     * Finance refuses the cost, optionally naming the figure it would accept.
     *
     * <p><b>The linked offer round is left alone on purpose.</b> It stays DRAFT, and since
     * {@link #isOfferApproved} finds no APPROVED row it simply cannot be sent — the gate does
     * the work without this method having to touch the offer's own state machine. Marking the
     * round REJECTED here would overload a status that means "the candidate declined", which
     * is a different event with different consequences for the candidature.
     *
     * <p>Without a figure, RH's way forward is a new round: drafting one supersedes this one
     * and goes back through the queue. WITH a figure on an offer round, the caller
     * ({@link OfferService#decideByFinance}) turns it into the DAF's own round, sent straight
     * to the candidate (V133).
     */
    public CandidateCostApprovalDto reject(Long id, String notes, BigDecimal contrePropSalaire, Long approvedBy) {
        CandidateCostApproval approval = findPending(id);
        approval.setStatus("REJECTED");
        approval.setApprovedBy(approvedBy);
        approval.setApprovedAt(OffsetDateTime.now());
        approval.setApprovalNotes(notes);
        approval.setContrePropSalaire(contrePropSalaire);
        CandidateCostApproval saved = approvalRepo.save(approval);

        if (contrePropSalaire != null) {
            candidateRepo.findById(saved.getCandidateId()).ifPresent(candidate -> {
                candidate.setSalaireNetRh(contrePropSalaire);
                candidateRepo.save(candidate);
            });
        }

        return toDto(saved, candidateRepo.findById(saved.getCandidateId()).orElse(null));
    }

    private CandidateCostApproval findPending(Long id) {
        CandidateCostApproval approval = approvalRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Demande d'approbation introuvable : id=" + id));
        if (!"PENDING".equals(approval.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Cette demande a déjà été traitée (status=" + approval.getStatus() + ")");
        }
        return approval;
    }

    private CandidateCostApprovalDto toDto(CandidateCostApproval a, Candidate candidate) {
        CandidateCostApprovalDto dto = new CandidateCostApprovalDto();
        dto.setId(a.getId());
        dto.setCandidateId(a.getCandidateId());
        dto.setJobOfferId(a.getJobOfferId());
        dto.setProposedSalary(a.getProposedSalary());
        if (candidate != null) {
            dto.setCandidateFirstName(candidate.getFirstName());
            dto.setCandidateLastName(candidate.getLastName());
            dto.setAppliedPosition(candidate.getAppliedPosition());
            dto.setCandidateLocation(candidate.getLocation());
        }
        dto.setPaysId(a.getPaysId());
        dto.setFiscalYear(a.getFiscalYear());
        dto.setSalaireNetRh(a.getSalaireNetRh());
        dto.setSalaireNetCandidat(a.getSalaireNetCandidat());
        // Stored: the CONTRACT_TYPE list id. Exposed: its nature (the payroll-facing code) + id + label.
        dto.setContractTypeCode(contractTypeRefs.natureOf(a.getContractTypeId(), a.getPaysId()));
        contractTypeRefs.find(a.getContractTypeId(), a.getPaysId()).ifPresent(v -> {
            dto.setContractTypeId(v.getId());
            dto.setContractTypeLabel(v.getLabelFr());
        });
        dto.setSimulationSnapshot(a.getSimulationSnapshot());
        dto.setStatus(a.getStatus());
        dto.setSubmittedBy(a.getSubmittedBy());
        dto.setSubmittedAt(a.getSubmittedAt());
        dto.setApprovedBy(a.getApprovedBy());
        dto.setApprovedAt(a.getApprovedAt());
        dto.setApprovalNotes(a.getApprovalNotes());
        dto.setContrePropSalaire(a.getContrePropSalaire());
        return dto;
    }
}
