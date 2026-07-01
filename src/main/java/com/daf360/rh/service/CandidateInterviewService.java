package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.UpdateInterviewRequest;
import com.daf360.rh.dto.interview.UserPickerDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CandidateInterviewService {

    private final CandidateInterviewRepository interviewRepo;
    private final InterviewTypeRepository      typeRepo;
    private final CandidateRepository          candidateRepo;
    private final TenantService                tenantService;
    private final JdbcTemplate                 jdbcTemplate;

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public List<UserPickerDto> listInterviewUsers(Long paysId) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long filter = (effectivePaysId != null) ? effectivePaysId : paysId;
        return jdbcTemplate.query(
                "SELECT id, fullName FROM Users WHERE isActive = 1 AND pays_id = ? ORDER BY fullName",
                (rs, rn) -> new UserPickerDto(rs.getLong("id"), rs.getString("fullName")),
                filter);
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public List<CandidateInterviewDto> listByCandidate(Long candidateId) {
        loadCandidateWithTenantCheck(candidateId);
        List<CandidateInterview> interviews =
                interviewRepo.findByCandidateIdOrderBySequenceNumber(candidateId);
        Set<Long> typeIds = interviews.stream()
                .map(CandidateInterview::getInterviewTypeId)
                .collect(Collectors.toSet());
        Map<Long, String> typeNames = typeRepo.findAllById(typeIds).stream()
                .collect(Collectors.toMap(InterviewType::getId, InterviewType::getName));
        return interviews.stream()
                .map(ci -> toDto(ci, typeNames.get(ci.getInterviewTypeId())))
                .toList();
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public CandidateInterviewDto create(Long candidateId, CreateInterviewRequest req, Long actorId) {
        Candidate candidate = loadCandidateWithTenantCheck(candidateId);

        InterviewType type = typeRepo.findById(req.interviewTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("InterviewType", req.interviewTypeId()));

        if (!type.getPaysId().equals(candidate.getPaysId())) {
            throw new BusinessRuleException(
                    "Ce type d'entretien n'appartient pas à l'entité du candidat");
        }
        if (!Boolean.TRUE.equals(type.getIsActive())) {
            throw new BusinessRuleException(
                    "Ce type d'entretien est désactivé et ne peut pas être utilisé");
        }
        // Anti-duplication: user-friendly message before the DB filtered unique index fires
        if (interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                candidateId, req.interviewTypeId(), InterviewStatus.PLANNED)) {
            throw new BusinessRuleException(
                    "Un entretien de type '" + type.getName() + "' est déjà planifié pour ce candidat. "
                    + "Terminez ou annulez l'entretien existant avant d'en créer un nouveau.");
        }

        int sequenceNumber = (int) (interviewRepo.countByCandidateId(candidateId) + 1);

        CandidateInterview interview = CandidateInterview.builder()
                .candidateId(candidateId)
                .interviewTypeId(req.interviewTypeId())
                .scheduledAt(req.scheduledAt())
                .location(req.location())
                .interviewerNotes(req.interviewerNotes())
                .interviewerUserId(req.interviewerUserId())
                .status(InterviewStatus.PLANNED)
                .sequenceNumber(sequenceNumber)
                .createdBy(actorId)
                .createdAt(OffsetDateTime.now())
                .build();

        return toDto(interviewRepo.save(interview), type.getName());
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public CandidateInterviewDto update(Long interviewId, UpdateInterviewRequest req, Long actorId) {
        CandidateInterview interview = interviewRepo.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateInterview", interviewId));

        // Multi-tenant check via candidate
        loadCandidateWithTenantCheck(interview.getCandidateId());

        // result (PASS/FAIL) can only be set when status = DONE
        if (req.result() != null) {
            InterviewStatus targetStatus = (req.status() != null)
                    ? InterviewStatus.valueOf(req.status())
                    : interview.getStatus();
            if (targetStatus != InterviewStatus.DONE) {
                throw new BusinessRuleException(
                        "Le résultat (PASS/FAIL) ne peut être saisi qu'une fois l'entretien terminé (status = DONE)");
            }
        }

        if (req.scheduledAt() != null)      interview.setScheduledAt(req.scheduledAt());
        if (req.location() != null)         interview.setLocation(req.location());
        if (req.interviewerNotes() != null) interview.setInterviewerNotes(req.interviewerNotes());
        if (req.interviewerUserId() != null) interview.setInterviewerUserId(req.interviewerUserId());
        if (req.status() != null)           interview.setStatus(InterviewStatus.valueOf(req.status()));
        if (req.result() != null)           interview.setResult(InterviewResult.valueOf(req.result()));
        interview.setUpdatedAt(OffsetDateTime.now());

        CandidateInterview saved = interviewRepo.save(interview);
        String typeName = typeRepo.findById(saved.getInterviewTypeId())
                .map(InterviewType::getName).orElse(null);
        return toDto(saved, typeName);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Candidate loadCandidateWithTenantCheck(Long candidateId) {
        Candidate candidate = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidate", candidateId));
        Long effectivePaysId = tenantService.getEffectivePaysId();
        if (effectivePaysId != null && !effectivePaysId.equals(candidate.getPaysId())) {
            throw new BusinessRuleException("Accès refusé : ce candidat appartient à une autre entité");
        }
        return candidate;
    }

    private CandidateInterviewDto toDto(CandidateInterview ci, String typeName) {
        String interviewerName = null;
        if (ci.getInterviewerUserId() != null) {
            List<String> names = jdbcTemplate.query(
                    "SELECT fullName FROM Users WHERE id = ?",
                    (rs, rn) -> rs.getString("fullName"),
                    ci.getInterviewerUserId());
            interviewerName = names.isEmpty() ? null : names.get(0);
        }
        return new CandidateInterviewDto(
                ci.getId(),
                ci.getCandidateId(),
                ci.getInterviewTypeId(),
                typeName,
                ci.getScheduledAt(),
                ci.getLocation(),
                ci.getInterviewerNotes(),
                ci.getInterviewerUserId(),
                interviewerName,
                ci.getStatus().name(),
                ci.getResult() != null ? ci.getResult().name() : null,
                ci.getSequenceNumber(),
                ci.getCreatedAt());
    }
}
