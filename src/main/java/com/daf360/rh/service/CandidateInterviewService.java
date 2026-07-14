package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.MyInterviewEventDto;
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

        // Prevent double-booking the interviewer at the same time slot.
        assertInterviewerFree(req.interviewerUserId(), req.scheduledAt(), null);

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

        // Re-check double-booking if it's still a planned interview (time/interviewer may have changed).
        if (interview.getStatus() == InterviewStatus.PLANNED) {
            assertInterviewerFree(interview.getInterviewerUserId(), interview.getScheduledAt(), interview.getId());
        }

        CandidateInterview saved = interviewRepo.save(interview);
        String typeName = typeRepo.findById(saved.getInterviewTypeId())
                .map(InterviewType::getName).orElse(null);
        return toDto(saved, typeName);
    }

    /**
     * Interviews assigned to a given interviewer (the current user) within a date
     * range, as calendar events — used by the shell home calendar. Self-scoped, so
     * no RH_MANAGE_INTERVIEWS permission required. Returns [] when userId is null.
     * {@code from}/{@code to} are inclusive ISO dates (yyyy-MM-dd).
     */
    @Transactional(readOnly = true)
    public List<MyInterviewEventDto> listMyInterviews(Long userId, String from, String to) {
        if (userId == null) return List.of();
        String sql = """
            SELECT ci.id, ci.candidate_id, ci.scheduled_at, ci.location,
                   c.first_name, c.last_name, c.applied_position, it.name AS type_name
              FROM [dbo].[candidate_interviews] ci
              JOIN [dbo].[candidates] c ON c.id = ci.candidate_id
              LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id
             WHERE ci.interviewer_user_id = ?
               AND ci.status = 'PLANNED'
               AND ci.scheduled_at >= ?
               AND ci.scheduled_at <  DATEADD(day, 1, ?)
             ORDER BY ci.scheduled_at ASC
            """;
        return jdbcTemplate.query(sql, (rs, rn) -> {
            String first = rs.getString("first_name");
            String last  = rs.getString("last_name");
            String name  = ((first != null ? first : "") + " " + (last != null ? last : "")).trim();
            String type  = rs.getString("type_name");
            OffsetDateTime when = rs.getObject("scheduled_at", OffsetDateTime.class);
            String title = (type != null && !type.isBlank() ? type : "Entretien") + " · " + name;
            return new MyInterviewEventDto(
                    rs.getLong("id"),
                    rs.getLong("candidate_id"),
                    name,
                    rs.getString("applied_position"),
                    when,
                    rs.getString("location"),
                    title);
        }, userId, from, to);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static final int INTERVIEW_SLOT_MINUTES = 60;
    private static final java.time.format.DateTimeFormatter FR_DATETIME =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    /**
     * Rejects the operation if the interviewer already has a PLANNED interview within
     * one slot (~{@value #INTERVIEW_SLOT_MINUTES} min) of {@code scheduledAt}.
     * {@code excludeId} skips a given interview (used on update). No-op when no
     * interviewer or time is provided.
     */
    private void assertInterviewerFree(Long interviewerUserId, OffsetDateTime scheduledAt, Long excludeId) {
        if (interviewerUserId == null || scheduledAt == null) return;
        OffsetDateTime from = scheduledAt.minusMinutes(INTERVIEW_SLOT_MINUTES - 1L);
        OffsetDateTime to   = scheduledAt.plusMinutes(INTERVIEW_SLOT_MINUTES - 1L);
        List<CandidateInterview> conflicts = interviewRepo.findInterviewerConflicts(interviewerUserId, from, to)
                .stream()
                .filter(ci -> excludeId == null || !excludeId.equals(ci.getId()))
                .toList();
        if (!conflicts.isEmpty()) {
            String when = conflicts.get(0).getScheduledAt().format(FR_DATETIME);
            throw new BusinessRuleException(
                    "Cet intervieweur a déjà un entretien planifié le " + when
                    + " (créneau d'environ " + INTERVIEW_SLOT_MINUTES + " min). "
                    + "Choisissez un autre horaire ou un autre intervieweur.");
        }
    }

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
