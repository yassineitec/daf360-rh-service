package com.daf360.rh.service;

import com.daf360.rh.common.UserScope;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.CandidateInterviewInterviewer;
import com.daf360.rh.domain.CandidateInterviewReport;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewOpinion;
import com.daf360.rh.domain.enums.InterviewResult;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.AssignedInterviewDto;
import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.InterviewReportDto;
import com.daf360.rh.dto.interview.MyInterviewEventDto;
import com.daf360.rh.dto.interview.SubmitInterviewReportRequest;
import com.daf360.rh.dto.interview.UpdateInterviewRequest;
import com.daf360.rh.dto.interview.UserPickerDto;
import com.daf360.rh.dto.interview.ValidateInterviewRequest;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.notification.InterviewNotifier;
import com.daf360.rh.repository.CandidateInterviewInterviewerRepository;
import com.daf360.rh.repository.CandidateInterviewReportRepository;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.calendar.GraphCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
    private final GraphCalendarService          graphCalendarService;
    // ── V132: PVs, RH validation, notifications ──
    private final CandidateInterviewReportRepository reportRepo;
    private final InterviewAccess                    access;
    private final InterviewNotifier                  notifier;
    private final PaysTimezoneService                paysTimezoneService;

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public List<UserPickerDto> listInterviewUsers(Long paysId) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long filter = (effectivePaysId != null) ? effectivePaysId : paysId;
        return jdbcTemplate.query(
                "SELECT id, fullName FROM Users u " +
                "WHERE u.isActive = 1 AND u.pays_id = ? " +
                "AND " + UserScope.realPeople("u") + " " +
                "ORDER BY u.fullName",
                (rs, rn) -> new UserPickerDto(rs.getLong("id"), rs.getString("fullName")),
                filter);
    }

    /**
     * The candidate's interview timeline.
     *
     * <p>Open to RH_MANAGE_INTERVIEWS (who plans them), to the PV readers (V132), and to any
     * user sitting on one of this candidate's panels — an interviewer needs no RH code to see
     * the interview they were given. What each caller reads of the PVs is decided per row in
     * {@link #toDto}.
     */
    @Transactional(readOnly = true)
    public List<CandidateInterviewDto> listByCandidate(Long candidateId) {
        Candidate candidate = loadCandidateWithTenantCheck(candidateId);
        if (!access.canListInterviews(candidateId)) {
            throw new AccessDeniedException("Accès refusé aux entretiens de ce candidat");
        }
        List<CandidateInterview> interviews =
                interviewRepo.findByCandidateIdOrderBySequenceNumber(candidateId);
        return toDtos(interviews, candidate);
    }

    /** Builds the DTOs of several interviews of ONE candidate with batched lookups (no N+1). */
    private List<CandidateInterviewDto> toDtos(List<CandidateInterview> interviews, Candidate candidate) {
        if (interviews.isEmpty()) return List.of();
        Set<Long> typeIds = interviews.stream()
                .map(CandidateInterview::getInterviewTypeId)
                .collect(Collectors.toSet());
        Map<Long, InterviewType> types = typeRepo.findAllById(typeIds).stream()
                .collect(Collectors.toMap(InterviewType::getId, t -> t));
        List<Long> ids = interviews.stream().map(CandidateInterview::getId).toList();
        // Panels for the whole timeline in one query + one name lookup (no N+1).
        Map<Long, List<Long>> panels = panelsByInterview(ids);
        Map<Long, List<CandidateInterviewReport>> reports = reportsByInterview(ids);
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
        // PV authors and validators are named too — an author may since have left the panel.
        reports.values().forEach(list -> list.forEach(r -> nameIds.add(r.getAuthorUserId())));
        interviews.stream().map(CandidateInterview::getValidatedBy)
                .filter(java.util.Objects::nonNull).forEach(nameIds::add);
        Map<Long, String> names = userNames(nameIds);
        ViewCtx ctx = viewCtx(candidate);
        return interviews.stream()
                .map(ci -> toDto(ci, types.get(ci.getInterviewTypeId()),
                                 panels.getOrDefault(ci.getId(), List.of()),
                                 reports.getOrDefault(ci.getId(), List.of()), names, ctx))
                .toList();
    }

    @PreAuthorize("hasPermission(null, 'RH_MANAGE_INTERVIEWS')")
    public CandidateInterviewDto create(Long candidateId, CreateInterviewRequest req, Long actorId) {
        Candidate candidate = loadCandidateWithTenantCheck(candidateId);

        // Interviews are the first step of the process: no salary approval is waited on.
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
        // Mutated after save() — JPA dirty-checking flushes these at commit, no second save() needed.
        if (!panel.isEmpty()) {
            savePanel(saved.getId(), panel);
            syncCalendarEvent(saved, panel, candidate);
            notifier.assigned(panel, candidateId, fullName(candidate), type.getName(),
                    saved.getScheduledAt(), saved.getLocation());
        }
        return toDto(saved, type, panel, List.of(), userNames(panel), viewCtx(candidate));
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

        // V132: an interview is closed by its PVs, not by this form. It turns DONE when an
        // interviewer files their PV, and its result is RH's validation of those PVs — so
        // neither can be set here any more, or the PV step could simply be skipped.
        if (req.result() != null) {
            throw new BusinessRuleException(
                    "Le résultat d'un entretien est fixé par la validation RH de son PV, pas depuis ce formulaire");
        }
        if (req.status() != null && InterviewStatus.valueOf(req.status()) == InterviewStatus.DONE) {
            throw new BusinessRuleException(
                    "Un entretien passe à « terminé » quand un intervieweur dépose son PV");
        }

        // Before any change: who was on the panel, and when — to tell whoever is concerned.
        InterviewStatus statusBefore = interview.getStatus();
        OffsetDateTime slotBefore    = interview.getScheduledAt();
        List<Long> panelBefore       = effectivePanel(interview, panelOf(interview.getId()));

        if (req.interviewTypeId() != null && !req.interviewTypeId().equals(interview.getInterviewTypeId())) {
            InterviewType newType = assertUsableType(req.interviewTypeId(), candidate.getPaysId());
            assertNoOtherPlannedOfType(interview.getCandidateId(), newType, interview.getId());
            interview.setInterviewTypeId(req.interviewTypeId());
        }

        if (req.scheduledAt() != null)      interview.setScheduledAt(req.scheduledAt());
        if (req.location() != null)         interview.setLocation(req.location());
        if (req.interviewerNotes() != null) interview.setInterviewerNotes(req.interviewerNotes());
        if (req.status() != null)           interview.setStatus(InterviewStatus.valueOf(req.status()));
        interview.setUpdatedAt(OffsetDateTime.now());
        // A moved slot gets its own « dans une heure » reminder.
        boolean slotMoved = slotBefore != null && interview.getScheduledAt() != null
                && !slotBefore.isEqual(interview.getScheduledAt());
        if (slotMoved) interview.setReminderSentAt(null);

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

        // Mutated after save() — JPA dirty-checking flushes these at commit, no second save() needed.
        if (saved.getStatus() == InterviewStatus.CANCELLED) {
            cancelCalendarEvent(saved);
        } else if (saved.getStatus() == InterviewStatus.PLANNED) {
            if (!panel.isEmpty()) {
                syncCalendarEvent(saved, panel, candidate);
            } else {
                // Panel cleared while still PLANNED: nothing left to organize under —
                // treat exactly like a cancellation so the Graph event doesn't go orphaned.
                cancelCalendarEvent(saved);
            }
        }

        InterviewType type = typeRepo.findById(saved.getInterviewTypeId()).orElse(null);
        notifyPanelChanges(saved, candidate, type, statusBefore, panelBefore, panel, slotMoved);
        return toDto(saved, type, panel,
                reportsByInterview(List.of(saved.getId())).getOrDefault(saved.getId(), List.of()),
                userNames(panel), viewCtx(candidate));
    }

    /**
     * Who hears about an edit: a cancellation reaches the whole panel; otherwise a new member
     * is told they were assigned, a removed one that they no longer are, and the members who
     * stay are told only when the slot moved.
     */
    private void notifyPanelChanges(CandidateInterview saved, Candidate candidate, InterviewType type,
                                    InterviewStatus statusBefore, List<Long> panelBefore,
                                    List<Long> panelAfter, boolean slotMoved) {
        String name     = fullName(candidate);
        String typeName = type != null ? type.getName() : null;
        if (saved.getStatus() == InterviewStatus.CANCELLED) {
            if (statusBefore != InterviewStatus.CANCELLED) {
                notifier.cancelled(panelBefore, candidate.getId(), name, typeName, saved.getScheduledAt());
            }
            return;
        }
        if (saved.getStatus() != InterviewStatus.PLANNED) return;

        List<Long> added   = panelAfter.stream().filter(id -> !panelBefore.contains(id)).toList();
        List<Long> removed = panelBefore.stream().filter(id -> !panelAfter.contains(id)).toList();
        List<Long> kept    = panelAfter.stream().filter(panelBefore::contains).toList();

        notifier.assigned(added, candidate.getId(), name, typeName, saved.getScheduledAt(), saved.getLocation());
        notifier.unassigned(removed, name, typeName, saved.getScheduledAt());
        if (slotMoved) {
            notifier.rescheduled(kept, candidate.getId(), name, typeName, saved.getScheduledAt());
        }
    }

    // ── PV (V132) ─────────────────────────────────────────────────────────────

    /**
     * An interviewer files — or rewrites — their PV: a summary of the interview and their
     * opinion on the candidate.
     *
     * <p>No permission: the right is sitting on this interview's panel. Open once the slot has
     * started, closed once RH has validated (the decision was taken on what the PVs said).
     * The first PV turns the interview DONE; the RH validators are told each time a panel
     * member files theirs for the first time.
     */
    public CandidateInterviewDto submitReport(Long interviewId, SubmitInterviewReportRequest req, Long actorId) {
        CandidateInterview interview = interviewRepo.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateInterview", interviewId));
        Candidate candidate = loadCandidateWithTenantCheck(interview.getCandidateId());

        List<Long> panel = effectivePanel(interview, panelOf(interviewId));
        if (actorId == null || !panel.contains(actorId)) {
            throw new AccessDeniedException("Seuls les intervieweurs de cet entretien peuvent rédiger son PV");
        }
        if (interview.getStatus() == InterviewStatus.CANCELLED) {
            throw new BusinessRuleException("Cet entretien a été annulé : il n'y a pas de PV à rédiger");
        }
        if (interview.getResult() != null) {
            throw new BusinessRuleException(
                    "Cet entretien a déjà été validé par RH : les PV ne peuvent plus être modifiés");
        }
        if (!hasStarted(interview, candidate.getPaysId())) {
            throw new BusinessRuleException("Le PV se rédige une fois l'entretien commencé (prévu le "
                    + InterviewNotifier.formatSlot(interview.getScheduledAt()) + ")");
        }

        OffsetDateTime now = OffsetDateTime.now();
        var existing = reportRepo.findByInterviewIdAndAuthorUserId(interviewId, actorId);
        CandidateInterviewReport report = existing.orElseGet(() -> CandidateInterviewReport.builder()
                .interviewId(interviewId).authorUserId(actorId).submittedAt(now).build());
        report.setSummary(req.summary().trim());
        report.setOpinion(InterviewOpinion.valueOf(req.opinion()));
        if (existing.isPresent()) report.setUpdatedAt(now);
        reportRepo.save(report);

        if (interview.getStatus() == InterviewStatus.PLANNED) {
            interview.setStatus(InterviewStatus.DONE);
            interview.setUpdatedAt(now);
            interviewRepo.save(interview);
        }

        InterviewType type = typeRepo.findById(interview.getInterviewTypeId()).orElse(null);
        if (existing.isEmpty()) {
            notifier.reportSubmitted(candidate.getPaysId(), candidate.getId(), fullName(candidate),
                    type != null ? type.getName() : null,
                    userNames(List.of(actorId)).get(actorId));
        }
        return toDtos(List.of(interview), candidate).get(0);
    }

    /**
     * RH reads the PVs and decides: PASS lets the candidature move on, FAIL blocks it (and must
     * say why). Needs at least one PV — a decision with nothing written behind it is exactly
     * what this step exists to prevent. Final: a validated interview is a record.
     */
    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_INTERVIEW')")
    public CandidateInterviewDto validate(Long interviewId, ValidateInterviewRequest req, Long actorId) {
        CandidateInterview interview = interviewRepo.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("CandidateInterview", interviewId));
        Candidate candidate = loadCandidateWithTenantCheck(interview.getCandidateId());

        if (interview.getResult() != null) {
            throw new BusinessRuleException("Cet entretien a déjà été validé");
        }
        if (interview.getStatus() != InterviewStatus.DONE || reportRepo.countByInterviewId(interviewId) == 0) {
            throw new BusinessRuleException(
                    "Aucun PV n'a encore été déposé pour cet entretien : la validation se fait après lecture des PV");
        }
        InterviewResult decision = InterviewResult.valueOf(req.decision());
        String comment = req.comment() != null ? req.comment().trim() : null;
        if (decision == InterviewResult.FAIL && (comment == null || comment.isEmpty())) {
            throw new BusinessRuleException("Indiquez le motif de l'échec : il bloque la candidature");
        }

        interview.setResult(decision);
        interview.setValidatedBy(actorId);
        interview.setValidatedAt(OffsetDateTime.now());
        interview.setValidationComment(comment == null || comment.isEmpty() ? null : comment);
        interview.setUpdatedAt(OffsetDateTime.now());
        interviewRepo.save(interview);
        return toDtos(List.of(interview), candidate).get(0);
    }

    /**
     * « Mes entretiens »: every interview the caller sits on, cancelled ones excluded, from
     * four months back. Self-scoped, no permission. {@code myReportAt} says whether the
     * caller's PV is in.
     */
    @Transactional(readOnly = true)
    public List<AssignedInterviewDto> listAssigned(Long userId) {
        if (userId == null) return List.of();
        String sql = """
            SELECT ci.id, ci.candidate_id, ci.scheduled_at, ci.location, ci.status, ci.result,
                   ci.graph_join_url, c.first_name, c.last_name, c.applied_position,
                   it.name AS type_name, it.name_en AS type_name_en,
                   r.submitted_at AS my_report_at
              FROM [dbo].[candidate_interviews] ci
              JOIN [dbo].[candidates] c ON c.id = ci.candidate_id
              LEFT JOIN [dbo].[interview_types] it ON it.id = ci.interview_type_id
              LEFT JOIN [dbo].[candidate_interview_reports] r
                     ON r.interview_id = ci.id AND r.author_user_id = ?
             WHERE (ci.interviewer_user_id = ?
                    OR EXISTS (SELECT 1 FROM [dbo].[candidate_interview_interviewers] p
                                WHERE p.interview_id = ci.id AND p.user_id = ?))
               AND ci.status <> 'CANCELLED'
               AND ci.scheduled_at >= DATEADD(day, -120, SYSDATETIMEOFFSET())
             ORDER BY ci.scheduled_at DESC
            """;
        return jdbcTemplate.query(sql, (rs, rn) -> {
            String first = rs.getString("first_name");
            String last  = rs.getString("last_name");
            return new AssignedInterviewDto(
                    rs.getLong("id"),
                    rs.getLong("candidate_id"),
                    ((first != null ? first : "") + " " + (last != null ? last : "")).trim(),
                    rs.getString("applied_position"),
                    rs.getString("type_name"),
                    rs.getString("type_name_en"),
                    rs.getObject("scheduled_at", OffsetDateTime.class),
                    rs.getString("location"),
                    rs.getString("status"),
                    rs.getString("result"),
                    rs.getObject("my_report_at", OffsetDateTime.class),
                    rs.getString("graph_join_url"));
        }, userId, userId, userId);
    }

    // ── time ──────────────────────────────────────────────────────────────────

    /**
     * Has the slot started? The slot is stored as the wall clock RH typed, stamped +00:00, so
     * the real instant is that wall clock in the candidate's entity zone (UTC when unset).
     */
    private boolean hasStarted(CandidateInterview ci, Long paysId) {
        return realStart(ci.getScheduledAt(), paysId).isBefore(java.time.Instant.now().plusSeconds(1));
    }

    java.time.Instant realStart(OffsetDateTime stored, Long paysId) {
        java.time.ZoneId zone = paysId != null ? paysTimezoneService.zoneFor(paysId) : null;
        return stored.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime()
                .atZone(zone != null ? zone : java.time.ZoneOffset.UTC).toInstant();
    }

    private static String fullName(Candidate c) {
        return ((c.getFirstName() != null ? c.getFirstName() : "") + " "
                + (c.getLastName() != null ? c.getLastName() : "")).trim();
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

    /** What the caller may do, resolved once per request rather than per row. */
    private record ViewCtx(Long me, boolean readAllReports, boolean mayValidate, Long paysId) {}

    private ViewCtx viewCtx(Candidate candidate) {
        return new ViewCtx(access.currentUserId(), access.canReadAllReports(),
                access.has(com.daf360.rh.common.PermissionCatalog.RH_VALIDATE_INTERVIEW),
                candidate != null ? candidate.getPaysId() : null);
    }

    private Map<Long, List<CandidateInterviewReport>> reportsByInterview(Collection<Long> interviewIds) {
        if (interviewIds.isEmpty()) return Map.of();
        Map<Long, List<CandidateInterviewReport>> byInterview = new LinkedHashMap<>();
        for (CandidateInterviewReport r : reportRepo.findByInterviewIdInOrderBySubmittedAtAsc(interviewIds)) {
            byInterview.computeIfAbsent(r.getInterviewId(), k -> new ArrayList<>()).add(r);
        }
        return byInterview;
    }

    private CandidateInterviewDto toDto(CandidateInterview ci, InterviewType type, List<Long> panel,
                                        List<CandidateInterviewReport> reports,
                                        Map<Long, String> names, ViewCtx ctx) {
        List<Long> effective = effectivePanel(ci, panel);
        List<UserPickerDto> interviewers = effective.stream()
                .map(uid -> new UserPickerDto(uid, names.get(uid)))
                .toList();
        String leadName = ci.getInterviewerUserId() != null
                ? names.get(ci.getInterviewerUserId())
                : null;

        // An RH reader sees every PV; a panel member only their own — the others' opinions
        // must not colour theirs, and are not theirs to read.
        List<InterviewReportDto> visible = reports.stream()
                .filter(r -> ctx.readAllReports() || (ctx.me() != null && ctx.me().equals(r.getAuthorUserId())))
                .map(r -> new InterviewReportDto(r.getId(), r.getAuthorUserId(), names.get(r.getAuthorUserId()),
                        r.getSummary(), r.getOpinion().name(), r.getSubmittedAt(), r.getUpdatedAt()))
                .toList();
        boolean open = ci.getStatus() != InterviewStatus.CANCELLED && ci.getResult() == null;
        boolean canWrite = open && ctx.me() != null && effective.contains(ctx.me())
                && ci.getScheduledAt() != null && hasStarted(ci, ctx.paysId());
        boolean canValidate = ctx.mayValidate() && ci.getStatus() == InterviewStatus.DONE
                && ci.getResult() == null && !reports.isEmpty();

        return new CandidateInterviewDto(
                ci.getId(),
                ci.getCandidateId(),
                ci.getInterviewTypeId(),
                type != null ? type.getName() : null,
                type != null ? type.getNameEn() : null,
                ci.getScheduledAt(),
                ci.getLocation(),
                ci.getInterviewerNotes(),
                ci.getInterviewerUserId(),
                leadName,
                interviewers,
                ci.getStatus().name(),
                ci.getResult() != null ? ci.getResult().name() : null,
                ci.getSequenceNumber(),
                ci.getCreatedAt(),
                ci.getGraphJoinUrl(),
                visible,
                reports.size(),
                canWrite,
                canValidate,
                ci.getValidatedBy(),
                ci.getValidatedBy() != null ? names.get(ci.getValidatedBy()) : null,
                ci.getValidatedAt(),
                ci.getValidationComment());
    }

    // ── calendar sync (Outlook/Teams) ────────────────────────────────────────

    private static final int CALENDAR_EVENT_DURATION_MINUTES = 60;

    /** userId → email (COALESCE(email, username), same convention used elsewhere in
     * this codebase) for a batch of users, in one query — sibling of userNames(). */
    private Map<Long, String> userEmails(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<UserEmail> rows = jdbcTemplate.query(
                "SELECT id, COALESCE(email, username) AS email FROM Users WHERE id IN (" + placeholders + ")",
                (rs, rn) -> new UserEmail(rs.getLong("id"), rs.getString("email")),
                userIds.toArray());
        Map<Long, String> emails = new LinkedHashMap<>();
        rows.forEach(u -> emails.put(u.id(), u.email()));
        return emails;
    }

    private record UserEmail(Long id, String email) {}

    /** Best-effort: creates, patches, or (if the lead interviewer changed) recreates
     * the Graph calendar event for this interview. Never throws — a bug here must
     * never break interview creation/editing (GraphCalendarService itself also never
     * throws, but the surrounding email-resolution logic here could). */
    private void syncCalendarEvent(CandidateInterview interview, List<Long> panel, Candidate candidate) {
        try {
            Long leadUserId = panel.get(0);
            Map<Long, String> emails = userEmails(panel);
            String organizerEmail = emails.get(leadUserId);
            if (organizerEmail == null) {
                log.debug("Could not resolve the lead interviewer's email for interview {} — skipping calendar sync",
                        interview.getId());
                return;
            }

            // LinkedHashSet: avoids sending Graph a duplicate attendee if the candidate's
            // email happens to collide with a panel member's, or two panel members
            // resolve to the same fallback email — same dedup approach as normalizePanel().
            Set<String> attendeeEmails = new LinkedHashSet<>();
            for (int i = 1; i < panel.size(); i++) {
                String e = emails.get(panel.get(i));
                if (e != null) attendeeEmails.add(e);
            }
            if (candidate.getEmailPersonal() != null) attendeeEmails.add(candidate.getEmailPersonal());
            List<String> attendeeEmailList = new ArrayList<>(attendeeEmails);

            String subject = "Entretien - " + candidate.getFirstName() + " " + candidate.getLastName();
            OffsetDateTime start = interview.getScheduledAt();
            OffsetDateTime end = start.plusMinutes(CALENDAR_EVENT_DURATION_MINUTES);

            boolean organizerChanged = interview.getGraphEventId() != null
                    && interview.getGraphOrganizerEmail() != null
                    && !interview.getGraphOrganizerEmail().equals(organizerEmail);

            boolean needsCreate = interview.getGraphEventId() == null || organizerChanged;

            if (!needsCreate) {
                // Same organizer, event already exists — try to patch it in place.
                // EVENT_NOT_FOUND means it was genuinely deleted out from under us
                // (e.g. manually in Outlook) — safe to self-heal by falling through to
                // create a fresh one below. FAILED (network/auth/throttling/timeout —
                // an ambiguous, likely-transient failure) must NOT trigger a recreate:
                // the original PATCH may well have landed despite a timed-out response,
                // or the existing event may still be perfectly live — recreating here
                // would risk a duplicate invite while orphaning the original. Leave
                // graphEventId untouched; the next edit simply retries the patch.
                GraphCalendarService.UpdateOutcome outcome = graphCalendarService.updateEvent(
                        organizerEmail, interview.getGraphEventId(),
                        subject, start, end, attendeeEmailList, interview.getLocation());
                if (outcome == GraphCalendarService.UpdateOutcome.SUCCESS) {
                    return;
                } else if (outcome == GraphCalendarService.UpdateOutcome.EVENT_NOT_FOUND) {
                    needsCreate = true;
                } else {
                    return;
                }
            }

            if (needsCreate) {
                if (organizerChanged) {
                    graphCalendarService.cancelEvent(interview.getGraphOrganizerEmail(), interview.getGraphEventId(),
                            "Cet entretien a été réorganisé avec un nouvel intervieweur principal.");
                }
                graphCalendarService.createEvent(organizerEmail, subject, start, end, attendeeEmailList, interview.getLocation())
                        .ifPresentOrElse(created -> {
                            interview.setGraphEventId(created.eventId());
                            interview.setGraphOrganizerEmail(organizerEmail);
                            interview.setGraphJoinUrl(created.joinUrl());
                        }, () -> { /* failed or not configured — fields remain unchanged */ });
            }
        } catch (Exception e) {
            log.warn("Calendar sync failed for interview {}: {}",
                    interview.getId(), e.getMessage());
        }
    }

    /** Best-effort: cancels the Graph calendar event tied to this interview, if any. */
    private void cancelCalendarEvent(CandidateInterview interview) {
        try {
            if (interview.getGraphEventId() == null || interview.getGraphOrganizerEmail() == null) return;
            graphCalendarService.cancelEvent(interview.getGraphOrganizerEmail(), interview.getGraphEventId(),
                    "Cet entretien a été annulé.");
            interview.setGraphEventId(null);
            interview.setGraphOrganizerEmail(null);
            interview.setGraphJoinUrl(null);
        } catch (Exception e) {
            log.warn("Calendar cancellation failed for interview {}: {}",
                    interview.getId(), e.getMessage());
        }
    }
}
