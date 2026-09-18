package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateCostApproval;
import com.daf360.rh.domain.JobOffer;
import com.daf360.rh.lifecycle.ContractTypeBridge;
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
    /** Resolves the candidate's EMPLOYMENT_TYPE into the contract code the snapshot was run for. */
    private final ContractTypeBridge              contractTypeBridge;

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
                .contractTypeCode(contractTypeBridge.resolveContractTypeCode(
                        candidate.getEmploymentTypeId()))
                .simulationSnapshot(simulationSnapshot)
                .status("PENDING")
                .submittedBy(submittedBy)
                .submittedAt(OffsetDateTime.now())
                .build();
        return toDto(approvalRepo.save(approval), candidate);
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

    public CandidateCostApprovalDto submit(SubmitCostApprovalRequest req, Long submittedBy) {
        Candidate candidate = candidateRepo.findById(req.getCandidateId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Candidat introuvable : id=" + req.getCandidateId()));

        CandidateCostApproval approval = CandidateCostApproval.builder()
                .candidateId(req.getCandidateId())
                .paysId(req.getPaysId())
                .fiscalYear(req.getFiscalYear())
                .salaireNetRh(req.getSalaireNetRh())
                .salaireNetCandidat(req.getSalaireNetCandidat())
                .contractTypeCode(req.getContractTypeCode())
                .simulationSnapshot(req.getSimulationSnapshot())
                .status("PENDING")
                .submittedBy(submittedBy)
                .submittedAt(OffsetDateTime.now())
                .build();

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
     * <p>RH's way forward is a new round: drafting one supersedes this one and goes back
     * through the queue. The counter-proposal below is what that round starts from.
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
        dto.setContractTypeCode(a.getContractTypeCode());
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
