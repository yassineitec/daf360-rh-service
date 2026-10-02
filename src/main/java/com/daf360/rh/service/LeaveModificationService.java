package com.daf360.rh.service;

import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.domain.LeaveModificationRequest;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.ModificationState;
import com.daf360.rh.domain.enums.ModificationType;
import com.daf360.rh.dto.leave.LeaveModificationCreate;
import com.daf360.rh.dto.leave.LeaveModificationDecision;
import com.daf360.rh.dto.leave.LeaveModificationDto;
import com.daf360.rh.mapper.LeaveRequestMapper;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.LeaveModificationRequestRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proposals to move or cancel an ALREADY APPROVED congé.
 *
 * WHY THIS IS A SEPARATE SERVICE
 * -----------------------------------------------------------------------------
 * The workflow is not the congé workflow. A congé has one approval; a modification has two
 * stages decided by different people, and only the second one writes. Folding it into
 * {@link LeaveRequestService} — already the largest service here — would mean two state
 * machines in one class, with the congé's own transitions the harder to find of the two.
 *
 * WHAT IT DOES NOT OWN
 * -----------------------------------------------------------------------------
 * Balances. Approving a proposal moves days, and every rule about moving days — which
 * balance a type draws on, the −3 tolerance, the COALESCE that stops a NULL being erased —
 * lives in {@link LeaveRequestService}. This class decides WHETHER, and calls
 * {@code applyModification} / {@code archive} to decide HOW MUCH. The timesheet split it the
 * same way, and the one thing it got wrong is instructive: its cancellation path archived the
 * leave and refunded separately, so the two could disagree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveModificationService {

    private final LeaveModificationRequestRepository repository;
    private final LeaveRequestRepository leaves;
    private final LeaveRequestService leaveService;
    private final WorkingDayCalculator workingDays;
    private final LeaveRequestMapper mapper;
    private final TenantService tenantService;
    /** Admin-configurable recipients and wording — see notifyChange. */
    private final NotificationRoutingService routing;
    /** rh-service has no Users entity; names come through JDBC, as everywhere else here. */
    private final JdbcTemplate jdbc;

    // ═══ Filing a proposal ═══════════════════════════════════════════════════

    /**
     * File a proposal against one's own approved congé.
     *
     * Four things are refused here, each because approving it later would be incoherent:
     * a congé that is not approved (there is nothing settled to contest — edit the request
     * itself), somebody else's congé, a second open proposal on the same leave, and a
     * MODIFICATION whose proposed dates are missing or inverted.
     */
    @Transactional
    public LeaveModificationDto create(LeaveModificationCreate dto, Long callerId, String lang) {
        LeaveRequest leave = leaves.findById(dto.getAbsenceId())
                .orElseThrow(() -> new AppException(ErrorCode.ABSENCE_NOT_FOUND));

        if (!leave.getCollaborateurId().equals(callerId)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Vous ne pouvez demander la modification que de vos propres congés.");
        }
        if (leave.getEtatDemande() != DemandeEtat.VALIDE) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Seul un congé validé peut faire l'objet d'une demande de modification "
                    + "ou d'annulation. Une demande encore en attente se modifie directement.");
        }
        /*
         * A FINISHED CONGÉ CANNOT BE CONTESTED.
         *
         * `VALIDE` alone was not enough: a leave that ended last month is still VALIDE, so
         * without this an employee could cancel an absence they had already taken and be
         * refunded the days for it. The rule is the one HR would state — a congé may be
         * changed or called off while it is still ahead of you or still running, not after.
         *
         * The END date, not the start: somebody three days into a week off may legitimately
         * come back early, and that is a cancellation of the remainder, not a rewrite of
         * history.
         */
        if (leave.getDateFin() != null && leave.getDateFin().isBefore(LocalDate.now())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Ce congé est terminé : il ne peut plus être modifié ni annulé.");
        }
        // Checked for the message; UX_amr_one_open_per_absence (V111) is what holds under a
        // race, since two callers can both pass this before either commits.
        if (repository.hasOpenProposal(leave.getId())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une demande est déjà en cours sur ce congé.");
        }

        LeaveModificationRequest request = LeaveModificationRequest.builder()
                .absenceId(leave.getId())
                .collaborateurId(callerId)
                .paysId(leave.getPaysId())
                .type(dto.getType())
                .etat(dto.getType().initialState())
                .motif(dto.getMotif())
                .build();

        if (dto.getType().carriesDates()) {
            LocalDate from = dto.getNouvelleDateDebut();
            LocalDate to = dto.getNouvelleDateFin() == null ? from : dto.getNouvelleDateFin();
            if (from == null) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Une demande de modification doit porter de nouvelles dates.");
            }
            if (to.isBefore(from)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "La date de fin ne peut pas précéder la date de début.");
            }
            request.setNouvelleDateDebut(from);
            request.setNouvelleDateFin(to);
            // Recomputed, never taken from the client — the same rule the original submission
            // follows. The category comes from the leave being modified: a modification moves
            // a congé, it does not turn a half-day into a range.
            request.setNouveauTotal(
                    workingDays.billableDays(from, to, leave.getCategory(), leave.getPaysId()));
            // Only a modification has a manager stage, so only a modification carries the
            // manager. Copied from the leave rather than re-derived, so the proposal goes to
            // whoever granted it even if the hierarchy moves underneath.
            request.setResponsableId(leave.getResponsableId());
        }

        LeaveModificationRequest saved = repository.save(request);

        // A move waits on the responsable the congé already names; a cancellation skips them
        // and waits on HR. The event carries the subject either way — the HR recipient is a
        // PERMISSION rule on the same event (V113), so HR is reached without a subject.
        notifyChange("LEAVE_CHANGE_REQUESTED", saved, leave,
                saved.getType() == ModificationType.MODIFICATION ? saved.getResponsableId() : null);
        return mapper.toModificationDto(saved, leave, lang);
    }

    // ═══ Stage 1 — the manager ═══════════════════════════════════════════════

    /**
     * The manager's answer. MODIFICATION only — an ANNULATION never reaches this stage.
     *
     * Agreeing does NOT apply anything: it passes the proposal to HR, who are the ones that
     * write. That is why this method touches no balance.
     */
    @Transactional
    public LeaveModificationDto decideAsManager(Long id, LeaveModificationDecision dto,
                                                Long actorId, String lang) {
        LeaveModificationRequest request = require(id);

        if (request.getEtat() != ModificationState.PENDING_MANAGER) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Cette demande n'est pas en attente du responsable.");
        }
        /*
         * YOU MUST BE THE RESPONSABLE THIS PROPOSAL IS WAITING ON.
         *
         * The queue only ever lists your own, so this was invisible through the UI — but the
         * API is the boundary, not the list: holding RESPOND_MODIFICATION_MANAGER let anyone
         * decide any pending proposal by id. SETTLE_LEAVES stays an override, as it is
         * everywhere else in this module, so HR can unblock a manager who has left.
         */
        if (!tenantService.isAdmin()
                && !actorId.equals(request.getResponsableId())
                && !hasAuthority("SETTLE_LEAVES")) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Vous n'êtes pas le responsable de cette demande.");
        }

        request.setDecidedByManager(actorId);
        boolean agreed = Boolean.TRUE.equals(dto.getDecision());
        if (agreed) {
            request.setEtat(ModificationState.PENDING_HR);
        } else {
            reject(request, dto);
        }
        LeaveModificationRequest saved = repository.save(request);

        // Agreed -> HR has something to apply. Refused -> it ends here, so the employee is
        // the one who needs telling. Two different audiences, two different events.
        if (agreed) {
            notifyChange("LEAVE_CHANGE_FORWARDED", saved, null, null);
        } else {
            notifyChange("LEAVE_CHANGE_DECIDED", saved, null, saved.getCollaborateurId());
        }
        return toDto(saved, lang);
    }

    // ═══ Stage 2 — HR, and the only stage that writes ════════════════════════

    /**
     * HR's answer, and the point at which the congé actually changes.
     *
     * The order matters: the leave is updated BEFORE the proposal is marked approved, so a
     * failure in the balance adjustment rolls back both. Marking the proposal first and
     * applying after would leave an APPROVED proposal whose change never happened — the one
     * state nobody can recover from by looking at the data.
     */
    @Transactional
    public LeaveModificationDto decideAsHr(Long id, LeaveModificationDecision dto,
                                           Long actorId, String lang) {
        LeaveModificationRequest request = require(id);

        if (request.getEtat() != ModificationState.PENDING_HR) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Cette demande n'est pas en attente RH.");
        }
        /*
         * THE COUNTRY PERIMETER APPLIES TO THE DECISION, NOT ONLY TO THE LIST.
         *
         * hrQueue() filters on the role's pays scope (V74), so an out-of-scope proposal never
         * appears — but nothing stopped deciding one by id, and this is the stage that MOVES A
         * BALANCE. Scoping the read and not the write is how a boundary becomes decorative.
         */
        assertInScope(request.getPaysId());

        request.setDecidedByHr(actorId);
        request.setDateValidation(LocalDate.now());

        if (Boolean.FALSE.equals(dto.getDecision())) {
            reject(request, dto);
            LeaveModificationRequest refused = repository.save(request);
            notifyChange("LEAVE_CHANGE_DECIDED", refused, null, refused.getCollaborateurId());
            return toDto(refused, lang);
        }

        if (request.getType() == ModificationType.MODIFICATION) {
            leaveService.applyModification(
                    request.getAbsenceId(),
                    request.getNouvelleDateDebut(),
                    request.getNouvelleDateFin(),
                    request.getNouveauTotal(),
                    actorId);
        } else {
            // archive() already refunds an approved congé's days — the timesheet's
            // applyAnnulation archived and refunded through two separate paths, which is how
            // they came to disagree. One call, one behaviour.
            //
            // The proposal's motif travels onto the congé itself: a report reading absences
            // should not have to join back to the request that asked for the cancellation to
            // find out why it happened.
            leaveService.archive(request.getAbsenceId(), actorId, request.getMotif());
        }

        request.setEtat(ModificationState.APPROVED);
        LeaveModificationRequest saved = repository.save(request);
        notifyChange("LEAVE_CHANGE_DECIDED", saved, null, saved.getCollaborateurId());
        return toDto(saved, lang);
    }

    private void reject(LeaveModificationRequest request, LeaveModificationDecision dto) {
        String motif = dto.getMotifRefus() == null ? "" : dto.getMotifRefus().trim();
        if (motif.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Un motif est requis pour refuser une demande.");
        }
        request.setEtat(ModificationState.REJECTED);
        request.setMotifRefus(motif);
    }

    // ═══ The three queues ════════════════════════════════════════════════════

    /** The employee's own proposals, whatever their state. */
    @Transactional(readOnly = true)
    public Page<LeaveModificationDto> mine(Long userId, Pageable pageable, String lang) {
        return page(repository.findByCollaborateurId(userId, pageable), lang);
    }

    /** The manager's queue — PENDING_MANAGER only, so annulations never appear. */
    @Transactional(readOnly = true)
    public Page<LeaveModificationDto> managerQueue(Long managerId, Pageable pageable, String lang) {
        return page(repository.findByResponsableIdAndEtat(
                managerId, ModificationState.PENDING_MANAGER, pageable), lang);
    }

    /** HR's queue, inside the caller's country perimeter (V74). */
    @Transactional(readOnly = true)
    public Page<LeaveModificationDto> hrQueue(Pageable pageable, String lang) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        return page(repository.findForHr(ModificationState.PENDING_HR,
                scope.all() ? 1 : 0, scopeIds(scope), pageable), lang);
    }

    /**
     * The four figures the queue screens show, counted over the whole scope rather than the
     * current page — a KPI that changes when you turn the page is measuring the page.
     *
     * {@code manager} is personal (what is waiting on THIS manager); the other three are the
     * caller's country perimeter, which is what the HR queue works over.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(Long userId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        int all = scope.all() ? 1 : 0;
        List<Long> ids = scopeIds(scope);

        Map<String, Long> out = new HashMap<>();
        out.put("manager",  repository.countByResponsableIdAndEtat(
                userId, ModificationState.PENDING_MANAGER));
        out.put("hr",       repository.countForHr(ModificationState.PENDING_HR, all, ids));
        out.put("approved", repository.countForHr(ModificationState.APPROVED, all, ids));
        out.put("rejected", repository.countForHr(ModificationState.REJECTED, all, ids));
        return out;
    }

    /**
     * Every proposal filed against one congé — the history panel on its detail view.
     *
     * Scoped like the queues: the caller must own the congé, be one of its approvers, or hold
     * the right to read beyond themselves. Without this, anyone with GET_LEAVES could read any
     * employee's cancellation reasons by walking ids.
     */
    @Transactional(readOnly = true)
    public List<LeaveModificationDto> forLeave(Long absenceId, Long callerId, String lang) {
        LeaveRequest leave = leaves.findById(absenceId)
                .orElseThrow(() -> new AppException(ErrorCode.ABSENCE_NOT_FOUND));

        boolean mine = leave.getCollaborateurId().equals(callerId);
        boolean approver = callerId.equals(leave.getResponsableId())
                        || callerId.equals(leave.getResponsableAdjointId());
        if (!mine && !approver && !tenantService.isAdmin()
                && !hasAuthority("SETTLE_LEAVES")
                && !hasAuthority("GET_GLOBAL_LEAVES")
                && !hasAuthority("GET_EMPLOYEES_LEAVES")) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Ce congé ne vous concerne pas.");
        }

        List<LeaveModificationRequest> rows = repository.findByAbsenceIdOrderByCreatedAtDesc(absenceId);
        return mapper.toModificationDtos(rows, leavesById(rows), lang);
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    private Page<LeaveModificationDto> page(Page<LeaveModificationRequest> rows, String lang) {
        Map<Long, LeaveRequest> byId = leavesById(rows.getContent());
        return rows.map(r -> mapper.toModificationDtos(List.of(r), byId, lang).get(0));
    }

    /**
     * The congés a page of proposals stands against, in ONE query.
     *
     * Resolved here rather than by a {@code @ManyToOne} on the entity: every screen needs the
     * original beside the proposal, so a lazy association would be a guaranteed N+1 on a page
     * that is always rendered in full.
     */
    private Map<Long, LeaveRequest> leavesById(List<LeaveModificationRequest> rows) {
        Set<Long> ids = rows.stream()
                .map(LeaveModificationRequest::getAbsenceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return leaves.findAllById(ids).stream()
                .collect(Collectors.toMap(LeaveRequest::getId, l -> l));
    }

    /**
     * An empty scope would make {@code IN :scopeIds} invalid SQL, so it is given one id that
     * matches nothing. The caller sees an empty queue, which is the correct answer for
     * somebody whose role covers no country.
     */
    private List<Long> scopeIds(PaysScopeContext.Scope scope) {
        if (scope.all() || scope.paysIds().isEmpty()) {
            return List.of(-1L);
        }
        return new ArrayList<>(scope.paysIds());
    }

    // ═══ Notifications ═══════════════════════════════════════════════════════

    /**
     * Fires one modification event through the admin-configurable routing engine.
     *
     * Deep-links to the CONGÉ, not to the proposal: a recipient always wants to see the leave
     * being contested, and the proposal is only ever read beside it.
     *
     * {@code subjectUserId} may be null — LEAVE_CHANGE_FORWARDED has no single subject,
     * because it goes to whoever holds RESPOND_MODIFICATION_HR in that entity (a PERMISSION
     * rule, V113). The engine simply skips a SUBJECT recipient when none is supplied.
     *
     * Failure is swallowed: a routing problem must not roll back a decision that has already
     * moved a balance.
     */
    private void notifyChange(String eventCode, LeaveModificationRequest req,
                              LeaveRequest known, Long subjectUserId) {
        try {
            LeaveRequest leave = known != null
                    ? known
                    : leaves.findById(req.getAbsenceId()).orElse(null);

            Map<String, String> vars = new HashMap<>();
            vars.put("employee",   nameOf(req.getCollaborateurId()));
            vars.put("motif",      req.getMotif() == null ? "" : req.getMotif());
            vars.put("motifRefus", req.getMotifRefus() == null ? "" : "Motif : " + req.getMotifRefus());
            vars.put("decision",   req.getEtat() == ModificationState.REJECTED ? "refusée" : "acceptée");
            vars.put("type",       leave == null || leave.getType() == null ? "" : leave.getType());
            vars.put("period",     leave == null ? "" : periodOf(leave.getDateDebut(), leave.getDateFin()));
            vars.put("newPeriod",  req.getType() == ModificationType.ANNULATION
                    ? "Annulation demandée."
                    : "Nouvelle période : " + periodOf(req.getNouvelleDateDebut(), req.getNouvelleDateFin()) + ".");
            vars.put("delta",      req.getNouveauTotal() == null || leave == null || leave.getTotalJours() == null
                    ? "" : req.getNouveauTotal().subtract(leave.getTotalJours()).toPlainString());

            routing.resolveAndDispatch(RoutingContext.builder()
                    .eventCode(eventCode)
                    .paysId(req.getPaysId())
                    .subjectUserId(subjectUserId)
                    .entityType(NotificationEntityType.LEAVE)
                    .entityId(req.getAbsenceId())
                    .templateVars(vars)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Demande de modification {} : notification {} non envoyée — {}",
                    req.getId(), eventCode, e.toString());
        }
    }

    private String nameOf(Long userId) {
        if (userId == null) return "";
        try {
            String n = jdbc.queryForObject("SELECT fullName FROM Users WHERE id = ?", String.class, userId);
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String periodOf(LocalDate from, LocalDate to) {
        if (from == null) return "";
        return from.equals(to) ? from.toString() : from + " au " + to;
    }

    /**
     * Refuses a proposal outside the caller's country perimeter.
     *
     * A null {@code paysId} passes: rows written before V111 back-filled it carry none, and
     * refusing those would strand them with nobody able to decide. A global scope passes
     * everything, which is what ALL means.
     */
    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.all() || paysId == null) {
            return;
        }
        if (!scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette demande concerne une entité hors de votre périmètre.");
        }
    }

    /** Whether the caller holds a code, for the overrides this module grants SETTLE_LEAVES. */
    private boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> code.equals(a.getAuthority()));
    }

    private LeaveModificationRequest require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.REQUEST_NOT_FOUND));
    }

    private LeaveModificationDto toDto(LeaveModificationRequest r, String lang) {
        return mapper.toModificationDto(r, leaves.findById(r.getAbsenceId()).orElse(null), lang);
    }
}
