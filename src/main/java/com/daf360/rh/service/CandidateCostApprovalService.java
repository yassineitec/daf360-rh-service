package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateCostApproval;
import com.daf360.rh.dto.hiring.CandidateCostApprovalDto;
import com.daf360.rh.dto.hiring.SubmitCostApprovalRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.CandidateCostApprovalRepository;
import com.daf360.rh.repository.CandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
public class CandidateCostApprovalService {

    private final CandidateCostApprovalRepository approvalRepo;
    private final CandidateRepository             candidateRepo;

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

    public CandidateCostApprovalDto approve(Long id, String notes, Long approvedBy) {
        CandidateCostApproval approval = findPending(id);
        approval.setStatus("APPROVED");
        approval.setApprovedBy(approvedBy);
        approval.setApprovedAt(OffsetDateTime.now());
        approval.setApprovalNotes(notes);
        CandidateCostApproval saved = approvalRepo.save(approval);
        return toDto(saved, candidateRepo.findById(saved.getCandidateId()).orElse(null));
    }

    public CandidateCostApprovalDto reject(Long id, String notes, Long approvedBy) {
        CandidateCostApproval approval = findPending(id);
        approval.setStatus("REJECTED");
        approval.setApprovedBy(approvedBy);
        approval.setApprovedAt(OffsetDateTime.now());
        approval.setApprovalNotes(notes);
        CandidateCostApproval saved = approvalRepo.save(approval);
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
        if (candidate != null) {
            dto.setCandidateFirstName(candidate.getFirstName());
            dto.setCandidateLastName(candidate.getLastName());
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
        return dto;
    }
}
