package com.daf360.rh.service;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.CandidateInterviewInterviewer;
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
import com.daf360.rh.repository.CandidateInterviewInterviewerRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final CandidateInterviewInterviewerRepository panelRepo;
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
        // Panels for the whole timeline in one query + one name lookup (no N+1).
        Map<Long, List<Long>> panels = panelsByInterview(
                interviews.stream().map(CandidateInterview::getId).toList());
        // Le lead entre AUSSI dans la recherche de noms. Une ligne écrite avant V73 (ou dont
        // le backfill n'a pas tourné) porte `interviewer_user_id` sans aucune ligne de panel :
        // n'interroger que le panel laissait alors `interviewerName` à null, et l'intervieweur
        // disparaissait de l'écran alors qu'il est bien en base.
        Set<Long> nameIds = new LinkedHashSet<>(
                panels.values().stream().flatMap(List::stream).toList());
        interviews.stream()
                .map(CandidateInterview::getInterviewerUserId)
                .filter(java.util.Objects::nonNull)
                .forEach(nameIds::add);
        Map<Long, String> names = userNames(nameIds);
        return interviews.stream()
                .map(ci -> toDto(ci, typeNames.get(ci.getInterviewTypeId()),
                                 panels.getOrDefault(ci.getId(), List.of()), names))
                .toList();
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public CandidateInterviewDto create(Long candidateId, CreateInterviewRequest req, Long actorId) {
        Candidate candidate = loadCandidateWithTenantCheck(candidateId);

        InterviewType type = assertUsableType(req.interviewTypeId(), candidate.getPaysId());
        assertNoOtherPlannedOfType(candidateId, type, null);

        List<Long> panel = normalizePanel(req.interviewerUserIds());

        // Prevent double-booking any panel member at the same time slot.
        for (Long userId : panel) {
            assertInterviewerFree(userId, req.scheduledAt(), null);
        }

        int sequenceNumber = (int) (interviewRepo.countByCandidateId(candidateId) + 1);

        CandidateInterview interview = CandidateInterview.builder()
                .candidateId(candidateId)
                .interviewTypeId(req.interviewTypeId())
                .scheduledAt(req.scheduledAt())
                .location(req.location())
                .interviewerNotes(req.interviewerNotes())
                .interviewerUserId(panel.isEmpty() ? null : panel.get(0))
                .status(InterviewStatus.PLANNED)
                .sequenceNumber(sequenceNumber)
                .createdBy(actorId)
                .createdAt(OffsetDateTime.now())
                .build();

        CandidateInterview saved = interviewRepo.save(interview);
        if (!panel.isEmpty()) savePanel(saved.getId(), panel);
        return toDto(saved, type.getName(), panel, userNames(panel));
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public CandidateInterviewDto update(Long interviewId, UpdateInterviewRequest req, Long actorId) {
        CandidateInterview interview = interviewRepo.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateInterview", interviewId));

        // Multi-tenant check via candidate
        Candidate candidate = loadCandidateWithTenantCheck(interview.getCandidateId());

        // A finished interview is a record, not a draft: nothing on it can change anymore.
        if (interview.getStatus() == InterviewStatus.DONE) {
            throw new BusinessRuleException(
                    "Cet entretien est terminé et ne peut plus être modifié");
        }

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

        if (req.interviewTypeId() != null && !req.interviewTypeId().equals(interview.getInterviewTypeId())) {
            InterviewType newType = assertUsableType(req.interviewTypeId(), candidate.getPaysId());
            assertNoOtherPlannedOfType(interview.getCandidateId(), newType, interview.getId());
            interview.setInterviewTypeId(req.interviewTypeId());
        }

        if (req.scheduledAt() != null)      interview.setScheduledAt(req.scheduledAt());
        if (req.location() != null)         interview.setLocation(req.location());
        if (req.interviewerNotes() != null) interview.setInterviewerNotes(req.interviewerNotes());
        if (req.status() != null)           interview.setStatus(InterviewStatus.valueOf(req.status()));
        if (req.result() != null)           interview.setResult(InterviewResult.valueOf(req.result()));
        interview.setUpdatedAt(OffsetDateTime.now());

        // A null panel means "leave it alone"; an empty list clears it.
        List<Long> panel = req.interviewerUserIds() != null
                ? normalizePanel(req.interviewerUserIds())
                : effectivePanel(interview, panelOf(interview.getId()));
        if (req.interviewerUserIds() != null) {
            interview.setInterviewerUserId(panel.isEmpty() ? null : panel.get(0));
        }

        // Re-check double-booking if it's still a planned interview (time/panel may have changed).
        if (interview.getStatus() == InterviewStatus.PLANNED) {
            for (Long userId : panel) {
                assertInterviewerFree(userId, interview.getScheduledAt(), interview.getId());
            }
        }

        CandidateInterview saved = interviewRepo.save(interview);
        if (req.interviewerUserIds() != null) savePanel(saved.getId(), panel);
        String typeName = typeRepo.findById(saved.getInterviewTypeId())
                .map(InterviewType::getName).orElse(null);
        return toDto(saved, typeName, panel, userNames(panel));
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
             WHERE (ci.interviewer_user_id = ?
                    OR EXISTS (SELECT 1 FROM [dbo].[candidate_interview_interviewers] p
                                WHERE p.interview_id = ci.id AND p.user_id = ?))
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
        }, userId, userId, from, to);
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
            // Name the clashing interviewer: with a panel, "cet intervieweur" is ambiguous.
            String who = userNames(List.of(interviewerUserId))
                    .getOrDefault(interviewerUserId, "Cet intervieweur");
            throw new BusinessRuleException(
                    who + " a déjà un entretien planifié le " + when
                    + " (créneau d'environ " + INTERVIEW_SLOT_MINUTES + " min). "
                    + "Choisissez un autre horaire ou un autre intervieweur.");
        }
    }

    /** Loads a type and rejects it if it belongs to another entity or is deactivated. */
    private InterviewType assertUsableType(Long typeId, Long candidatePaysId) {
        InterviewType type = typeRepo.findById(typeId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewType", typeId));
        if (!type.getPaysId().equals(candidatePaysId)) {
            throw new BusinessRuleException(
                    "Ce type d'entretien n'appartient pas à l'entité du candidat");
        }
        if (!Boolean.TRUE.equals(type.getIsActive())) {
            throw new BusinessRuleException(
                    "Ce type d'entretien est désactivé et ne peut pas être utilisé");
        }
        return type;
    }

    /**
     * Anti-duplication: at most one PLANNED interview per type per candidate. Raised here
     * with a readable message before the DB filtered unique index fires. {@code excludeId}
     * skips the interview being edited.
     */
    private void assertNoOtherPlannedOfType(Long candidateId, InterviewType type, Long excludeId) {
        boolean clash = (excludeId == null)
                ? interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                        candidateId, type.getId(), InterviewStatus.PLANNED)
                : interviewRepo.findByCandidateIdOrderBySequenceNumber(candidateId).stream()
                        .anyMatch(ci -> ci.getStatus() == InterviewStatus.PLANNED
                                     && type.getId().equals(ci.getInterviewTypeId())
                                     && !excludeId.equals(ci.getId()));
        if (clash) {
            throw new BusinessRuleException(
                    "Un entretien de type '" + type.getName() + "' est déjà planifié pour ce candidat. "
                    + "Terminez ou annulez l'entretien existant avant d'en créer un nouveau.");
        }
    }

    // ── interview panel ───────────────────────────────────────────────────────

    /** Drops nulls and duplicates while keeping the caller's order — index 0 is the lead. */
    private List<Long> normalizePanel(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return new ArrayList<>(userIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private List<Long> panelOf(Long interviewId) {
        return panelRepo.findByInterviewIdOrderByIdAsc(interviewId).stream()
                .map(CandidateInterviewInterviewer::getUserId)
                .toList();
    }

    private Map<Long, List<Long>> panelsByInterview(Collection<Long> interviewIds) {
        if (interviewIds.isEmpty()) return Map.of();
        Map<Long, List<Long>> byInterview = new LinkedHashMap<>();
        for (CandidateInterviewInterviewer p : panelRepo.findByInterviewIdInOrderByIdAsc(interviewIds)) {
            byInterview.computeIfAbsent(p.getInterviewId(), k -> new ArrayList<>()).add(p.getUserId());
        }
        return byInterview;
    }

    /** Replaces the stored panel wholesale — simpler than diffing, and the sets are tiny. */
    private void savePanel(Long interviewId, List<Long> userIds) {
        panelRepo.deleteByInterviewId(interviewId);
        // Explicit flush: Hibernate orders inserts before deletes at commit, so keeping the
        // same interviewer across an edit would otherwise collide with the unique index.
        panelRepo.flush();
        if (userIds.isEmpty()) return;
        panelRepo.saveAll(userIds.stream()
                .map(uid -> CandidateInterviewInterviewer.builder()
                        .interviewId(interviewId).userId(uid).build())
                .toList());
    }

    /** userId → fullName for a batch of users, in one query. */
    private Map<Long, String> userNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<UserPickerDto> rows = jdbcTemplate.query(
                "SELECT id, fullName FROM Users WHERE id IN (" + placeholders + ")",
                (rs, rn) -> new UserPickerDto(rs.getLong("id"), rs.getString("fullName")),
                userIds.toArray());
        Map<Long, String> names = new LinkedHashMap<>();
        rows.forEach(u -> names.put(u.id(), u.fullName()));
        return names;
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

    /**
     * Le panel exposé n'est JAMAIS vide quand un lead existe.
     *
     * Ce n'est pas un détail d'affichage : le formulaire d'édition côté client se réamorce
     * sur `interviewers` et renvoie la liste telle quelle comme nouveau panel. Une ligne
     * d'avant V73 (lead renseigné, aucune ligne de panel) revenait donc avec
     * `interviewers: []`, et le simple fait de corriger le lieu d'un entretien effaçait
     * silencieusement son intervieweur — panel vidé ET `interviewer_user_id` remis à null.
     *
     * On retombe donc sur le lead, ce qui rend aussi le backfill de V73 non bloquant pour
     * l'affichage.
     */
    private List<Long> effectivePanel(CandidateInterview ci, List<Long> panel) {
        if (!panel.isEmpty()) return panel;
        return ci.getInterviewerUserId() != null ? List.of(ci.getInterviewerUserId()) : List.of();
    }

    private CandidateInterviewDto toDto(CandidateInterview ci, String typeName,
                                        List<Long> panel, Map<Long, String> names) {
        List<UserPickerDto> interviewers = effectivePanel(ci, panel).stream()
                .map(uid -> new UserPickerDto(uid, names.get(uid)))
                .toList();
        String leadName = ci.getInterviewerUserId() != null
                ? names.get(ci.getInterviewerUserId())
                : null;
        return new CandidateInterviewDto(
                ci.getId(),
                ci.getCandidateId(),
                ci.getInterviewTypeId(),
                typeName,
                ci.getScheduledAt(),
                ci.getLocation(),
                ci.getInterviewerNotes(),
                ci.getInterviewerUserId(),
                leadName,
                interviewers,
                ci.getStatus().name(),
                ci.getResult() != null ? ci.getResult().name() : null,
                ci.getSequenceNumber(),
                ci.getCreatedAt());
    }
}
