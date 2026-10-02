package com.daf360.rh.service;

import com.daf360.rh.domain.AbsenceType;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.leave.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.AbsenceTypeRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The congé rules, ported from the timesheet's {@code AbsenceServiceImpl}.
 *
 * WHAT IS FAITHFUL
 * -----------------------------------------------------------------------------
 * The behaviour employees and managers see is unchanged: the -3 day tolerance, the debit at
 * approval rather than at submission, the edit window, refusal motives, archive rather than
 * delete, and bulk approve continuing past individual failures.
 *
 * WHAT CHANGED, AND WHY
 * -----------------------------------------------------------------------------
 *   1. totalJours is COMPUTED HERE. The timesheet let the browser count the range and
 *      debited whatever number arrived, so a caller could choose what their leave cost.
 *      Same rule, moved to where it cannot be edited. See {@link WorkingDayCalculator}.
 *
 *   2. Overlap is ENFORCED. The timesheet only fed existing ranges to the date picker; two
 *      overlapping requests submitted around it were both accepted.
 *
 *   3. The approver is CHECKED at decision time. The dropdown was the only thing deciding
 *      who could approve, and a dropdown is a convenience, not a control.
 *
 * All three are cases where a rule already existed and only its enforcement was missing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveRequestService {

    /**
     * How far below zero a balance may be driven. A request is refused when
     * {@code solde - requested < -3}.
     *
     * Carried across exactly, including the sign and the strictness of the comparison: at
     * precisely -3 the request is allowed, at -3.5 it is not. It applies to CONGE and to
     * MALADIE alike — the timesheet's form was stricter on MALADIE (hiding it at <= 0) while
     * its server used -3 for both, and the server's rule is the one that governed what could
     * actually be saved.
     */
    private static final BigDecimal TOLERANCE = new BigDecimal("-3");

    private final LeaveRequestRepository repository;
    private final AbsenceTypeRepository absenceTypes;
    private final LeaveApproverService approvers;
    private final WorkingDayCalculator workingDays;
    private final JdbcTemplate jdbcTemplate;
    /** Resolves the caller's pays scope from the token — see global() above. */
    private final TenantService tenantService;
    /** Local-first storage plus the best-effort SharePoint mirror — see attachJustification. */
    private final EmployeeDocumentService documents;
    /** Admin-configurable recipients and wording — see notifyLeave. */
    private final NotificationRoutingService routing;

    // ═══ The request form ════════════════════════════════════════════════════

    /**
     * Everything the modal needs, in one call.
     *
     * Including the country's holiday calendar and weekend pattern: without them the date
     * picker offers public holidays as bookable days and the form's cost preview counts them,
     * so the employee is quoted a figure the server will not agree with.
     */
    @Transactional(readOnly = true)
    public LeaveHeadersDto headers(Long userId, String lang) {
        Long paysId = paysOf(userId);
        return new LeaveHeadersDto(
                balancesOf(userId),
                typeOptions(userId, paysId, lang),
                Arrays.stream(LeaveCategory.values())
                        .map(c -> new LeaveOptionDto(c.getLabel(lang), c.name())).toList(),
                approvers.approversFor(userId),
                blockingRanges(userId),
                workingDays.holidayNames(paysId, LocalDate.now().minusMonths(6),
                                         LocalDate.now().plusMonths(18), lang),
                workingDays.weekendFor(paysId).stream().map(Enum::name)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

    @Transactional(readOnly = true)
    public List<LeaveBlockingRangeDto> blockingRanges(Long userId) {
        return repository.findBlockingRanges(userId, LocalDate.now().minusYears(1)).stream()
                .map(l -> new LeaveBlockingRangeDto(
                        l.getId(), l.getDateDebut(), l.getDateFin(), l.getEtatDemande().name()))
                .toList();
    }

    // ═══ Creating ════════════════════════════════════════════════════════════

    /** The employee's own request, from self-service. */
    @Transactional
    public LeaveRequest create(LeaveRequestCreate dto, Long actorId) {
        return createFor(dto, actorId, actorId, false);
    }

    /**
     * HR creating or régularising a request on someone else's behalf.
     *
     * {@code settle} skips neither the balance rule nor the overlap rule — régularisation
     * exists to correct a record, not to make an impossible one. What it does allow is
     * creating against a past date, which the employee's own form does not.
     */
    @Transactional
    public LeaveRequest createForEmployee(LeaveRequestCreate dto, Long collaborateurId, Long actorId) {
        return createFor(dto, collaborateurId, actorId, true);
    }

    private LeaveRequest createFor(LeaveRequestCreate dto, Long collaborateurId, Long actorId, boolean settle) {
        LocalDate from = dto.getDateDebut();
        LocalDate to   = dto.getCategory().isRange() && dto.getDateFin() != null
                ? dto.getDateFin()
                : dto.getDateDebut();

        if (to.isBefore(from)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "La date de fin est antérieure à la date de début.");
        }

        Long paysId = paysOf(collaborateurId);

        // Rule 2 — enforced, not merely displayed.
        if (repository.countOverlapping(collaborateurId, from, to, null) > 0) {
            throw new AppException(ErrorCode.LEAVE_OVERLAP);
        }

        BigDecimal total = workingDays.billableDays(from, to, dto.getCategory(), paysId);
        if (total.signum() <= 0) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Cette période ne contient aucun jour ouvré.");
        }

        AbsenceType type = requireType(dto.getType());
        validateTypeRules(type, total, dto.getJustificatif());
        // NOT applied to a régularisation: it records leave already taken, so demanding three
        // working days' notice for a day off last month would refuse every real case.
        if (!settle) {
            validateScheduling(type, collaborateurId, paysId, from, to, null);
        }
        assertBalanceAllows(collaborateurId, type, total);

        LeaveRequest entity = LeaveRequest.builder()
                .collaborateurId(collaborateurId)
                .responsableId(dto.getResponsableId())
                .responsableAdjointId(dto.getResponsableAdjointId())
                .paysId(paysId)
                .type(dto.getType())
                .category(dto.getCategory())
                .dateDebut(from)
                .dateFin(to)
                .totalJours(total)
                .justificatif(dto.getJustificatif())
                .reason(dto.getReason())
                .etatDemande(DemandeEtat.EN_ATTENTE)
                // Set on EVERY request, not just régularisations: "filed by the employee" is
                // a fact worth recording too, and it is what makes `createdBy <> collaborateur`
                // a reliable test rather than one that also matches rows we simply never wrote.
                .createdBy(actorId)
                .build();

        LeaveRequest saved = repository.save(entity);
        log.info("Leave request {} created for user {} by {} ({} {} days, {} to {}){}",
                saved.getId(), collaborateurId, actorId, dto.getType(), total, from, to,
                settle ? " [régularisation]" : "");

        /*
         * Both approvers are told, as two dispatches.
         *
         * The routing engine's SUBJECT mode resolves ONE user, and a congé may name a
         * responsable and a deputy who may each decide it. Telling only the first would make
         * the deputy a fallback nobody can use, since they would never learn there was
         * anything to fall back on.
         *
         * A régularisation notifies nobody: HR filed it already approved, so there is no
         * queue it lands in and no decision anyone is waiting to make.
         */
        if (!settle) {
            notifyLeave("LEAVE_SUBMITTED", saved, saved.getResponsableId(), Map.of());
            notifyLeave("LEAVE_SUBMITTED", saved, saved.getResponsableAdjointId(), Map.of());
        }
        return saved;
    }

    // ═══ Editing ═════════════════════════════════════════════════════════════

    /**
     * Only a pending request may be edited — unless the caller holds SETTLE_LEAVES, who may
     * edit one in any state. That exception is what makes régularisation possible at all.
     *
     * Recomputes the total, because changing the dates or the category changes what the
     * request costs, and a stale total would be debited at approval.
     */
    @Transactional
    public LeaveRequest update(Long id, LeaveRequestCreate dto, Long actorId, boolean canSettle) {
        LeaveRequest existing = require(id);

        if (!existing.isPending() && !canSettle) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être modifiée.");
        }
        /*
         * WHO MAY EDIT: the employee, one of the two approvers THEY chose, or HR.
         *
         * The approvers were missing, and that was wrong by the process rather than by the
         * code: a responsable who may APPROVE a request — committing the employee's balance —
         * could not correct a date on it first, and had to refuse it and ask for a resubmit.
         *
         * Approver, not "any manager": the right follows the request's own responsable /
         * responsable adjoint, so it cannot reach a congé this person was never asked to
         * decide.
         */
        boolean isOwner    = existing.getCollaborateurId().equals(actorId);
        boolean isApprover = actorId != null
                && (actorId.equals(existing.getResponsableId())
                 || actorId.equals(existing.getResponsableAdjointId()));
        if (!canSettle && !isOwner && !isApprover) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette demande appartient à un autre collaborateur.");
        }

        LocalDate from = dto.getDateDebut();
        LocalDate to   = dto.getCategory().isRange() && dto.getDateFin() != null
                ? dto.getDateFin()
                : dto.getDateDebut();

        if (repository.countOverlapping(existing.getCollaborateurId(), from, to, id) > 0) {
            throw new AppException(ErrorCode.LEAVE_OVERLAP);
        }

        BigDecimal total = workingDays.billableDays(from, to, dto.getCategory(), existing.getPaysId());

        // Only re-check the balance while the request is still pending: an approved request
        // has ALREADY been debited, and re-validating would compare the new total against a
        // balance the old one is already missing from.
        AbsenceType type = requireType(dto.getType());
        validateTypeRules(type, total, dto.getJustificatif());
        if (existing.isPending()) {
            assertBalanceAllows(existing.getCollaborateurId(), type, total);
        }

        /*
         * AN APPROVED REQUEST HAS ALREADY MOVED A BALANCE, SO EDITING IT MUST MOVE IT AGAIN.
         * -----------------------------------------------------------------------------
         * This was missing, and it silently corrupted the solde: changing an approved 5-day
         * congé to 10 days left the employee debited 5, keeping 5 days they had spent. The
         * comment above correctly explains why the request must not be RE-VALIDATED against
         * the balance — and that reasoning was mistakenly carried over to the ADJUSTMENT,
         * which is a different thing.
         *
         * Two cases, because the type can change too:
         *   same type     — move only the DIFFERENCE, like applyModification. One write, and
         *                   it never dips the balance below where it will settle.
         *   type changed  — the two types may draw on different balances (congé vs maladie vs
         *                   télétravail, per AbsenceType.balanceField), so the old one is
         *                   refunded in full and the new one debited in full. A delta across
         *                   two different columns is meaningless.
         *
         * Only for a request that is already VALIDE: a pending one has never been debited.
         */
        if (existing.getEtatDemande() == DemandeEtat.VALIDE) {
            AbsenceType oldType = requireType(existing.getType());
            BigDecimal oldTotal = existing.getTotalJours() == null ? BigDecimal.ZERO : existing.getTotalJours();

            if (oldType.getId().equals(type.getId())) {
                BigDecimal delta = total.subtract(oldTotal);
                if (delta.signum() > 0)      debit(existing.getCollaborateurId(), type, delta);
                else if (delta.signum() < 0) refund(existing.getCollaborateurId(), type, delta.negate());
            } else {
                refund(existing.getCollaborateurId(), oldType, oldTotal);
                debit(existing.getCollaborateurId(), type, total);
            }
        }

        existing.setType(dto.getType());
        existing.setCategory(dto.getCategory());
        existing.setDateDebut(from);
        existing.setDateFin(to);
        existing.setTotalJours(total);
        existing.setJustificatif(dto.getJustificatif());
        existing.setReason(dto.getReason());
        if (dto.getResponsableId() != null) existing.setResponsableId(dto.getResponsableId());
        existing.setResponsableAdjointId(dto.getResponsableAdjointId());

        return repository.save(existing);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    /**
     * Approve or refuse.
     *
     * The balance moves HERE and nowhere else. A pending request holds no days: that is why
     * an employee can have several outstanding and why refusing one costs nothing to undo.
     */
    @Transactional
    public LeaveRequest decide(Long id, LeaveDecisionRequest decision, Long actorId, boolean canSettle) {
        LeaveRequest request = require(id);

        if (!request.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Cette demande a déjà été traitée.");
        }
        // Rule 3 — the approver dropdown is a convenience; this is the control.
        if (!canSettle && !approvers.canDecide(actorId, request.getResponsableId(), request.getResponsableAdjointId())) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Vous n'êtes pas responsable de cette demande.");
        }

        if (Boolean.TRUE.equals(decision.getApproved())) {
            debit(request.getCollaborateurId(), requireType(request.getType()), request.getTotalJours());
            request.setEtatDemande(DemandeEtat.VALIDE);
            request.setMotifRefus(null);
        } else {
            if (decision.getMotifRefus() == null || decision.getMotifRefus().isBlank()) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un motif est obligatoire pour refuser une demande.");
            }
            request.setEtatDemande(DemandeEtat.REFUSE);
            request.setMotifRefus(decision.getMotifRefus());
        }

        request.setDateValidation(LocalDate.now());
        request.setDecidedBy(actorId);
        LeaveRequest saved = repository.save(request);

        // The employee learns the verdict. Until this existed they found out by opening the
        // history and noticing — which is how the timesheet's EmailAspect earned its keep.
        notifyLeave("LEAVE_DECIDED", saved, saved.getCollaborateurId(), Map.of(
                "decision",   Boolean.TRUE.equals(decision.getApproved()) ? "validé" : "refusé",
                "approver",   nameOf(actorId),
                "motifRefus", saved.getMotifRefus() == null ? "" : "Motif : " + saved.getMotifRefus()));
        return saved;
    }

    /**
     * Approve a whole queue.
     *
     * Each request is decided independently and a failure is recorded rather than thrown:
     * one employee's exhausted balance must not block the other nineteen decisions. This is
     * why it does not simply loop over {@link #decide} inside one transaction — a rollback
     * would undo the approvals that did succeed.
     */
    @Transactional
    public BulkApproveResultDto bulkApprove(Long managerId, LocalDate from, LocalDate to,
                                            Long collaborateurId, String type, String search,
                                            List<Long> selectedIds, boolean canSettle) {
        String term = normalise(search);

        // THE FILTERED SET IS ALWAYS RESOLVED, even when the caller names ids.
        //
        // An explicit selection is INTERSECTED with it rather than trusted: the ids arrive
        // from a browser, and `decide` on its own only checks that this manager may rule on a
        // request — not that the request was one the queue would have shown. Without the
        // intersection, a crafted id list could approve a type configured as hidden from
        // managers, or somebody else's row. Resolving the queue costs one indexed query.
        List<Long> visible = repository.findPendingIdsForManager(managerId, from, to,
                collaborateurId, type, term, searchIds(term), hiddenFromManagers());

        List<Long> ids;
        if (selectedIds == null || selectedIds.isEmpty()) {
            ids = visible;
        } else {
            java.util.Set<Long> allowed = new java.util.HashSet<>(visible);
            ids = selectedIds.stream().filter(allowed::contains).toList();
            if (ids.size() < selectedIds.size()) {
                // Not an error: a row decided by the adjoint a moment ago legitimately drops
                // out. Logged because a large gap means the screen and the server disagree.
                log.info("Bulk approve by {}: {} of {} selected ids are no longer in the queue",
                        managerId, selectedIds.size() - ids.size(), selectedIds.size());
            }
        }

        int approved = 0;
        List<BulkApproveResultDto.Failure> failures = new ArrayList<>();

        LeaveDecisionRequest yes = new LeaveDecisionRequest();
        yes.setApproved(Boolean.TRUE);

        for (Long id : ids) {
            try {
                decide(id, yes, managerId, canSettle);
                approved++;
            } catch (AppException e) {
                failures.add(new BulkApproveResultDto.Failure(id, nameOf(collaborateurOf(id)), e.getMessage()));
            }
        }
        log.info("Bulk approve by {}: {} approved, {} failed of {} targeted ({})",
                managerId, approved, failures.size(), ids.size(),
                selectedIds == null || selectedIds.isEmpty() ? "whole filtered queue" : "explicit selection");
        return new BulkApproveResultDto(approved, failures.size(), failures);
    }

    /**
     * Archive — the only removal there is. Nothing is hard-deleted, because a congé is part
     * of an employee's record even once cancelled.
     *
     * Refunds an approved request's days. The timesheet archived without refunding, so
     * cancelling an approved congé silently cost the employee the balance.
     */
    @Transactional
    public LeaveRequest archive(Long id, Long actorId) {
        return archive(id, actorId, null);
    }

    /** Same, recording why — see {@link LeaveRequest#getMotifAnnulation()}. */
    @Transactional
    public LeaveRequest archive(Long id, Long actorId, String motifAnnulation) {
        LeaveRequest request = require(id);

        if (request.getEtatDemande() == DemandeEtat.VALIDE) {
            refund(request.getCollaborateurId(), requireType(request.getType()), request.getTotalJours());
        }
        request.setEtatDemande(DemandeEtat.ARCHIVE);
        request.setDecidedBy(actorId);
        if (motifAnnulation != null && !motifAnnulation.isBlank()) {
            request.setMotifAnnulation(motifAnnulation.trim());
        }
        return repository.save(request);
    }

    /**
     * The employee takes back a request nobody has decided yet.
     *
     * NOT the same act as {@link #archive}, which is HR's power to remove ANY congé and is
     * gated on DELETE_LEAVE. This is the employee's own, gated on ADD_LEAVE: whoever may file
     * may unfile, and until this existed somebody who filed by mistake had to wait for a
     * manager to refuse it.
     *
     * NO REFUND, and that is not an oversight: a pending request has never been debited — the
     * debit happens in {@link #decide} at approval. Refunding here would credit days that were
     * never taken.
     *
     * Routed through ARCHIVE rather than a delete, because a congé is part of the employee's
     * record even once withdrawn.
     */
    @Transactional
    public LeaveRequest withdraw(Long id, String motif, Long actorId) {
        LeaveRequest request = require(id);

        if (!request.getCollaborateurId().equals(actorId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette demande appartient à un autre collaborateur.");
        }
        if (!request.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être retirée. Un congé déjà validé "
                    + "s'annule par une demande d'annulation.");
        }
        if (motif == null || motif.isBlank()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Un motif est requis pour retirer une demande.");
        }

        request.setEtatDemande(DemandeEtat.ARCHIVE);
        request.setDecidedBy(actorId);
        request.setMotifAnnulation(motif.trim());
        LeaveRequest saved = repository.save(request);

        // The approvers are told why it left their queue. A request that simply vanishes
        // reads as a bug; one that says "withdrawn, because…" reads as a decision.
        notifyLeave("LEAVE_WITHDRAWN", saved, saved.getResponsableId(),
                Map.of("motif", motif.trim()));
        notifyLeave("LEAVE_WITHDRAWN", saved, saved.getResponsableAdjointId(),
                Map.of("motif", motif.trim()));
        return saved;
    }

    /**
     * Move an approved congé to new dates, adjusting the balance by the DIFFERENCE.
     *
     * Called by {@link LeaveModificationService} when HR approves a MODIFICATION proposal.
     * It lives here, not there, because the balance rules live here: which balance a type
     * draws on ({@code balanceField}), the −3 tolerance, and the COALESCE that stops a NULL
     * balance being erased. A second class adjusting balances its own way is how the two
     * drift.
     *
     * ONLY THE DELTA MOVES. Refunding the old total and debiting the new one would be two
     * writes where one will do, and would fail the tolerance check in the gap for an employee
     * already near their limit — their balance would dip by the old total before the refund
     * landed, which is an artefact of the implementation rather than anything they did.
     *
     * A SHORTER leave gives days back and is always allowed; a LONGER one is a fresh draw and
     * is checked against the tolerance like any other.
     *
     * Recomputing the total is the CALLER's job — it needs the entity's weekend pattern and
     * holiday calendar, which the proposal carries and this method does not re-derive.
     */
    @Transactional
    public LeaveRequest applyModification(Long id, LocalDate newFrom, LocalDate newTo,
                                          BigDecimal newTotal, Long actorId) {
        LeaveRequest request = require(id);

        if (request.getEtatDemande() != DemandeEtat.VALIDE) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Seul un congé validé peut être modifié.");
        }

        AbsenceType type = requireType(request.getType());
        BigDecimal oldTotal = request.getTotalJours() == null ? BigDecimal.ZERO : request.getTotalJours();
        BigDecimal delta = newTotal.subtract(oldTotal);

        if (delta.signum() > 0) {
            debit(request.getCollaborateurId(), type, delta);
        } else if (delta.signum() < 0) {
            refund(request.getCollaborateurId(), type, delta.negate());
        }

        request.setDateDebut(newFrom);
        request.setDateFin(newTo);
        request.setTotalJours(newTotal);
        request.setDecidedBy(actorId);
        return repository.save(request);
    }

    // ═══ Balances ════════════════════════════════════════════════════════════

    // ═══ The catalogue ═══════════════════════════════════════════════════════

    /**
     * Resolve a submitted or stored type code.
     *
     * Does not filter on `active`: a retired type must still resolve, or archiving an old
     * request would fail to refund its days and a report could not label it.
     */
    // ═══ Notifications ═══════════════════════════════════════════════════════

    /**
     * Fires one congé event through the admin-configurable routing engine.
     *
     * NOT a hardcoded recipient. Who receives this is a row in
     * notification_routing_recipients, retunable in Administration → Notifications without a
     * deploy — the same engine the recruitment demands and the salary advances use. All this
     * method decides is WHO THE EVENT IS ABOUT ({@code subjectUserId}), because that is
     * per-event business data the engine cannot know: the chosen approver for the events that
     * travel up, the employee for the ones that come back.
     *
     * Failure is swallowed deliberately. A notification that cannot be routed must not roll
     * back an approved congé whose days have already moved — the decision is the transaction,
     * the message is a consequence of it.
     */
    private void notifyLeave(String eventCode, LeaveRequest leave, Long subjectUserId,
                             Map<String, String> extraVars) {
        if (subjectUserId == null) {
            return;
        }
        try {
            Map<String, String> vars = new java.util.HashMap<>(Map.of(
                    "employee", nameOf(leave.getCollaborateurId()),
                    "type",     typeLabelOf(leave.getType()),
                    "period",   periodOf(leave),
                    "days",     leave.getTotalJours() == null ? "" : leave.getTotalJours().toPlainString()));
            vars.putAll(extraVars);

            routing.resolveAndDispatch(RoutingContext.builder()
                    .eventCode(eventCode)
                    .paysId(leave.getPaysId())
                    .subjectUserId(subjectUserId)
                    .entityType(NotificationEntityType.LEAVE)
                    .entityId(leave.getId())
                    .templateVars(vars)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Congé {} : notification {} non envoyée — {}", leave.getId(), eventCode, e.toString());
        }
    }

    /** The catalogue label, falling back to the code — a retired type must still render. */
    private String typeLabelOf(String code) {
        if (code == null) return "";
        return absenceTypes.findByCode(code).map(t -> t.getLabelFr()).orElse(code);
    }

    private String periodOf(LeaveRequest l) {
        if (l.getDateDebut() == null) return "";
        return l.getDateDebut().equals(l.getDateFin())
                ? l.getDateDebut().toString()
                : l.getDateDebut() + " au " + l.getDateFin();
    }

    private AbsenceType requireType(String code) {
        return absenceTypes.findByCode(code)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_TRANSITION,
                        "Type de congé inconnu : " + code));
    }

    /**
     * The selectable types, each carrying its own approver list.
     *
     * Types that name approver roles resolve their own eligible people for THIS employee;
     * those that do not get null, and the form falls back to the generic manager list. The
     * distinction matters — see {@link LeaveTypeOptionDto}.
     */
    private List<LeaveTypeOptionDto> typeOptions(Long userId, Long paysId, String lang) {
        List<LeaveTypeOptionDto> out = new ArrayList<>();
        for (AbsenceType t : absenceTypes.findSelectable()) {
            int roleCount = approvers.approverRoleCount(t.getCode());
            List<LeaveApproverDto> eligible = roleCount > 0
                    ? approvers.eligibleApproversFor(t.getCode(), userId)
                    : null;
            out.add(new LeaveTypeOptionDto(
                    t.getCode(),
                    t.getLabel(lang),
                    roleCount == 1,
                    roleCount,
                    eligible,
                    Boolean.TRUE.equals(t.getTracksBalance()),
                    t.getBalanceField(),
                    Boolean.TRUE.equals(t.getRequiresJustification()),
                    t.getMaxDays(),
                    schedulingRuleFor(t.getAdvanceNoticeDays(), paysId, "advance_notice_days"),
                    schedulingRuleFor(t.getLeaveGapDays(), paysId, "leave_gap_days"),
                    t.getDescription()));
        }
        return out;
    }

    /** Codes and labels only — what a filter dropdown needs. */
    @Transactional(readOnly = true)
    public List<LeaveOptionDto> typeCatalogue(String lang) {
        return absenceTypes.findAllLive().stream()
                .map(t -> new LeaveOptionDto(t.getLabel(lang), t.getCode()))
                .toList();
    }

    // ═══ Type-driven rules ═══════════════════════════════════════════════════

    /**
     * The per-type rules: the day cap and the mandatory justification.
     *
     * Both live on the type rather than in code, so HR changes them without a deployment —
     * which is the whole reason the catalogue became a table.
     */
    private void validateTypeRules(AbsenceType type, BigDecimal days, Boolean justificatif) {
        if (type.exceedsMaxDays(days)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Ce type de congé est limité à " + type.getMaxDays() + " jour(s).");
        }
        if (Boolean.TRUE.equals(type.getRequiresJustification()) && !Boolean.TRUE.equals(justificatif)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Ce type de congé exige un justificatif.");
        }
    }

    /**
     * The tolerance check, now driven by the type rather than by a hardcoded pair.
     *
     * A type debits a balance only when it says so ({@code tracksBalance}), and which balance
     * is {@code balanceField} — so a new type drawing on congé is a row, not a code change.
     * The -3 tolerance itself is unchanged.
     *
     * A NULL balance is treated as zero HERE and only here. 135 of DAF360_HR's 260 users have
     * no congé balance recorded, and the timesheet's {@code user.getSoldeConge() - requested}
     * throws a NullPointerException on every one of them — the arithmetic unboxes null. Zero
     * is the honest reading: no allowance has been granted.
     */
    private void assertBalanceAllows(Long userId, AbsenceType type, BigDecimal requested) {
        if (!type.drawsOnBalance()) {
            return;
        }
        BigDecimal current = BigDecimal.valueOf(balanceOf(userId, type));
        if (current.subtract(requested).compareTo(TOLERANCE) < 0) {
            throw new AppException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT);
        }
    }

    /** Re-checks the tolerance before writing — the balance may have moved since submission. */
    private void debit(Long userId, AbsenceType type, BigDecimal days) {
        if (!type.drawsOnBalance()) return;
        assertBalanceAllows(userId, type, days);
        addToBalance(userId, type, days.negate());
    }

    private void refund(Long userId, AbsenceType type, BigDecimal days) {
        if (!type.drawsOnBalance()) return;
        addToBalance(userId, type, days);
    }

    /**
     * Maps {@code balanceField} to the column it names.
     *
     * An allow-list, not string interpolation: {@code balanceField} is administrator-supplied
     * data that would otherwise reach a SQL statement. An unrecognised value is a
     * configuration error and is refused rather than guessed at.
     */
    private String balanceColumn(AbsenceType type) {
        String field = type.getBalanceField();
        if ("CONGE".equalsIgnoreCase(field))    return "soldeConge";
        if ("MALADIE".equalsIgnoreCase(field))  return "soldeMaladie";
        if ("TELETRAVAIL".equalsIgnoreCase(field)) return "soldeTeletravail";
        throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Le type « " + type.getCode() + " » suit un solde inconnu : " + field);
    }

    /**
     * Balances live on {@code Users}, which rh-service has no entity for — the same reason
     * MissionService reads users through JdbcTemplate.
     *
     * COALESCE in the UPDATE, not just the SELECT: adding to a NULL balance yields NULL in
     * SQL, which would erase the column rather than set it.
     */
    private void addToBalance(Long userId, AbsenceType type, BigDecimal delta) {
        String column = balanceColumn(type);
        int rows = jdbcTemplate.update(
                "UPDATE Users SET " + column + " = COALESCE(" + column + ", 0) + ? WHERE id = ?",
                delta.doubleValue(), userId);
        if (rows == 0) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "Collaborateur " + userId + " introuvable pour la mise à jour du solde.");
        }
    }

    private double balanceOf(Long userId, AbsenceType type) {
        Double v = jdbcTemplate.queryForObject(
                "SELECT " + balanceColumn(type) + " FROM Users WHERE id = ?", Double.class, userId);
        return v == null ? 0d : v;
    }

    @Transactional(readOnly = true)
    public LeaveBalancesDto balancesOf(Long userId) {
        List<LeaveBalancesDto> rows = jdbcTemplate.query(
                "SELECT soldeConge, soldeMaladie, soldeTeletravail FROM Users WHERE id = ?",
                (rs, n) -> new LeaveBalancesDto(
                        (Double) rs.getObject("soldeConge"),
                        (Double) rs.getObject("soldeMaladie"),
                        (Double) rs.getObject("soldeTeletravail")),
                userId);
        // Nulls are preserved deliberately — see LeaveBalancesDto.
        return rows.isEmpty() ? new LeaveBalancesDto(null, null, null) : rows.get(0);
    }

    // ═══ Calendar ════════════════════════════════════════════════════════════

    /**
     * The caller's own leave overlapping a window, for the shell's home calendar.
     *
     * Answers for the caller only, so it needs no permission beyond being signed in —
     * the same rule as the missions calendar feed.
     */
    @Transactional(readOnly = true)
    public List<LeaveCalendarEventDto> myCalendar(Long userId, LocalDate from, LocalDate to, String lang) {
        Map<String, String> labels = new java.util.HashMap<>();
        absenceTypes.findAllLive().forEach(t -> labels.put(t.getCode(), t.getLabel(lang)));
        return repository.findOverlapping(userId, from, to).stream()
                .map(l -> new LeaveCalendarEventDto(
                        l.getId(),
                        l.getType(),
                        labels.getOrDefault(l.getType(), l.getType()),
                        l.getDateDebut(),
                        l.getDateFin(),
                        l.getTotalJours(),
                        l.getEtatDemande().name(),
                        l.getCategory().name()))
                .toList();
    }

    // ═══ Reading ═════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<LeaveRequest> myRequests(Long userId, Pageable pageable) {
        return repository.findByCollaborateurIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * A manager sees their queue MINUS the types the catalogue marks private.
     *
     *  is how a medically sensitive absence stays between the
     * employee and HR. The exclusion is applied here rather than in the query so the same
     * rule covers the team history below without being written twice.
     */
    @Transactional(readOnly = true)
    public Page<LeaveRequest> managerQueue(Long managerId, DemandeEtat etat, LocalDate from, LocalDate to,
                                           Long collaborateurId, String type, String search,
                                           Pageable pageable) {
        String term = normalise(search);
        return repository.findForManager(managerId, etat, from, to, collaborateurId, type,
                term, searchIds(term), hiddenFromManagers(), pageable);
    }

    /** Counts per state across the manager's whole queue — the KPI row, not the page. */
    @Transactional(readOnly = true)
    public Map<String, Long> queueCounts(Long managerId) {
        return zeroFilled(repository.countByStateForManager(managerId, hiddenFromManagers()));
    }

    /**
     * The search box, resolved to employee ids.
     *
     * `Users` is not a JPA entity in this service — names are batch-resolved through
     * JdbcTemplate in LeaveRequestMapper for the same reason — so a name cannot be matched in
     * JPQL by joining. Resolving the term to ids first keeps the four list queries free of a
     * native join and lets them stay `Page<LeaveRequest>`.
     *
     * Always returns at least the `-1L` placeholder: an empty list makes `IN` invalid in JPQL,
     * and a term that matches nobody must match no rows rather than fail the query.
     */
    private List<Long> searchIds(String term) {
        if (term == null) return List.of(-1L);
        List<Long> ids = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id FROM Users WHERE LOWER(fullName) LIKE ?",
                ps -> ps.setString(1, "%" + term.toLowerCase() + "%"),
                rs -> { ids.add(rs.getLong("id")); });
        if (ids.isEmpty()) ids.add(-1L);
        return ids;
    }

    /** Blank and whitespace are not a search term — they are the absence of one. */
    private String normalise(String search) {
        return (search == null || search.isBlank()) ? null : search.trim();
    }

    /** Every state present, so the KPI row never shrinks when a count happens to be zero. */
    private Map<String, Long> zeroFilled(List<Object[]> rows) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (DemandeEtat e : DemandeEtat.values()) counts.put(e.name(), 0L);
        for (Object[] row : rows) {
            counts.put(((DemandeEtat) row[0]).name(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    // ═══ Justificatif ════════════════════════════════════════════════════════

    /** The `document_types` code every leave justification is filed under (V110). */
    public static final String JUSTIFICATION_DOC_TYPE = "JUSTIFICATIF_ABSENCE";

    /**
     * Files the supporting document for a request and links it.
     *
     * DELEGATES to {@link EmployeeDocumentService}, which already stores the bytes locally as
     * the source of truth and then mirrors them to SharePoint best-effort. That split is what
     * makes this safe to enable before the SharePoint paths are configured: an unconfigured
     * or unreachable tenant costs the mirror, never the upload, and the file stays
     * downloadable from disk.
     *
     * The destination folder is therefore whatever Administration → SharePoint has assigned
     * to the `JUSTIFICATIF_ABSENCE` document type for that employee's country — no new
     * configuration surface, and no path hardcoded here.
     *
     * @throws AppException when the employee has no HR profile: `employee_documents` hangs off
     *         a profile, and there is nowhere to file the document without one.
     */
    @Transactional
    public LeaveRequest attachJustification(Long requestId, MultipartFile file,
                                            Authentication auth) throws java.io.IOException {
        LeaveRequest request = require(requestId);
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, "Aucun fichier reçu.");
        }

        Long profileId = profileIdOf(request.getCollaborateurId());
        if (profileId == null) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "Ce collaborateur n'a pas de dossier RH : impossible de classer un justificatif.");
        }

        var saved = documents.upload(profileId, file, JUSTIFICATION_DOC_TYPE, null,
                "Justificatif du congé #" + requestId, auth);

        request.setJustificatifDocumentId(saved.getId());
        request.setJustificatif(Boolean.TRUE);
        log.info("Justification {} attached to leave request {} (document {})",
                file.getOriginalFilename(), requestId, saved.getId());
        return repository.save(request);
    }

    /** The stored file, for download. Empty when nothing is attached. */
    @Transactional(readOnly = true)
    public Optional<EmployeeDocumentService.DownloadPayload> justification(Long requestId) {
        LeaveRequest request = require(requestId);
        Long docId = request.getJustificatifDocumentId();
        if (docId == null) return Optional.empty();

        Long profileId = profileIdOf(request.getCollaborateurId());
        if (profileId == null) return Optional.empty();
        try {
            return Optional.of(documents.download(profileId, docId));
        } catch (AppException e) {
            // A soft-deleted document leaves a dangling id — see LeaveRequest.justificatifDocumentId.
            // "No file" is the honest answer; a 500 would say the request itself is broken.
            log.info("Leave request {} points at document {}, which is gone: {}",
                    requestId, docId, e.getMessage());
            return Optional.empty();
        }
    }

    /** `employee_profiles.id` for a user, or null when they have no HR file. */
    private Long profileIdOf(Long userId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT id FROM employee_profiles WHERE user_id = ? AND deleted = 0",
                Long.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ═══ Scheduling rules ════════════════════════════════════════════════════

    /**
     * The two per-type scheduling rules, ported from the timesheet and moved onto the type.
     *
     *   NOTICE   the leave must start after N working days from today.
     *   GAP      the leave must not start within N working days of an existing leave's end.
     *
     * Both count WORKING days — weekends and public holidays do not consume notice, which is
     * the whole point of asking for "three days' notice" rather than "three days".
     *
     * Each value comes from the type, falling back to the country (V106) when the type says
     * nothing; null or 0 at both levels means the rule is off. Nothing fires until somebody
     * configures a value, and today every row at both levels is null.
     *
     * @param excludeId the request being edited, so a leave is never blocked by itself.
     */
    private void validateScheduling(AbsenceType type, Long collaborateurId, Long paysId,
                                    LocalDate from, LocalDate to, Long excludeId) {
        Integer notice = schedulingRuleFor(type.getAdvanceNoticeDays(), paysId, "advance_notice_days");
        Integer gap    = schedulingRuleFor(type.getLeaveGapDays(),      paysId, "leave_gap_days");
        if ((notice == null || notice <= 0) && (gap == null || gap <= 0)) {
            return;
        }

        if (notice != null && notice > 0) {
            LocalDate earliest = workingDays.addWorkingDays(LocalDate.now(), notice, paysId);
            // `isAfter`, not `!isBefore`: "three working days' notice" means the leave starts
            // after the third one, which is how the timesheet read it too.
            if (!from.isAfter(earliest)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Ce congé doit être demandé plus à l'avance : un préavis de "
                        + notice + " jour(s) ouvré(s) est requis pour « " + type.getLabel("fr") + " ».");
            }
        }

        if (gap != null && gap > 0) {
            // The same pending-or-approved set the form greys out, so the rule and the picker
            // are talking about the same leaves. A year back is plenty: a gap is measured in
            // working days, never months.
            for (LeaveRequest other : repository.findBlockingRanges(
                    collaborateurId, LocalDate.now().minusYears(1))) {
                if (excludeId != null && excludeId.equals(other.getId())) continue;
                if (other.getDateDebut() == null || other.getDateFin() == null) continue;
                LocalDate blockUntil = workingDays.addWorkingDays(other.getDateFin(), gap, paysId);
                // Overlap between [from, to] and [otherStart, blockUntil] — the requested
                // range must clear the tail of the existing leave, not merely its last day.
                if (!from.isAfter(blockUntil) && !to.isBefore(other.getDateDebut())) {
                    throw new AppException(ErrorCode.INVALID_TRANSITION,
                            "Ce congé est trop proche d'un autre congé : un délai de "
                            + gap + " jour(s) ouvré(s) est requis après le congé du "
                            + other.getDateDebut() + " au " + other.getDateFin() + ".");
                }
            }
        }
    }

    /** The type's value, or the country's when the type says nothing. */
    private Integer schedulingRuleFor(Integer typeValue, Long paysId, String paysColumn) {
        if (typeValue != null) return typeValue;
        if (paysId == null) return null;
        // One tiny read per rule, and only when the type left it unset. `paysColumn` is a
        // literal chosen by the two call sites above, never anything from a request.
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT " + paysColumn + " FROM pays WHERE id = ?", Integer.class, paysId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Codes a manager may not see. Returned as an EXCLUSION list, and never empty: an empty
     * IN-list is invalid in JPQL, so a sentinel keeps the predicate well-formed when every
     * type is visible.
     */
    private List<String> hiddenFromManagers() {
        List<String> hidden = new ArrayList<>(absenceTypes.findCodesHiddenFromManagers());
        if (hidden.isEmpty()) hidden.add("__none__");
        return hidden;
    }

    @Transactional(readOnly = true)
    public Page<LeaveRequest> teamHistory(Long managerId, DemandeEtat etat, String type,
                                          LocalDate from, LocalDate to, Long collaborateurId,
                                          String search, Pageable pageable) {
        List<Long> ids = approvers.subordinateUserIds(managerId);
        if (ids.isEmpty()) {
            return Page.empty(pageable);
        }
        String term = normalise(search);
        return repository.findForEmployees(ids, etat, type, from, to, collaborateurId,
                term, searchIds(term), hiddenFromManagers(), pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> teamCounts(Long managerId) {
        List<Long> ids = approvers.subordinateUserIds(managerId);
        if (ids.isEmpty()) return zeroFilled(List.of());
        return zeroFilled(repository.countByStateForEmployees(ids, hiddenFromManagers()));
    }

    /**
     * Country-wide history, bounded by the CALLER'S ROLE SCOPE.
     *
     * The scope comes from the token (V74) and is a ceiling, not a default: `paysId` is the
     * screen's own filter and applies on top of it, so asking for a country the role does not
     * cover returns nothing instead of revealing it. Before this, GET_GLOBAL_LEAVES read every
     * country regardless of the role's configured scope — EmployeeProfileService had applied
     * this since V74 and the leave module had never been brought in line.
     */
    @Transactional(readOnly = true)
    public Page<LeaveRequest> global(Long paysId, DemandeEtat etat, String type, LocalDate from,
                                     LocalDate to, Long collaborateurId, String search,
                                     Pageable pageable) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        String term = normalise(search);
        return repository.findGlobal(
                scope.unfiltered() ? 1 : 0, scope.idsOrPlaceholder(),
                paysId, etat, type, from, to, collaborateurId,
                term, searchIds(term), pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> globalCounts(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        return zeroFilled(repository.countByStateGlobal(
                scope.unfiltered() ? 1 : 0, scope.idsOrPlaceholder(), paysId));
    }

    /**
     * The régularisations — congés filed on someone else's behalf.
     *
     * `mineOnly` narrows to the caller's own. It is the screen's default, because the person
     * who filed a correction is the one who needs to check it went in right; clearing it
     * shows the whole company's, which is an audit view and deliberately a deliberate act.
     */
    @Transactional(readOnly = true)
    public Page<LeaveRequest> settled(Long actorId, boolean mineOnly, DemandeEtat etat, String type,
                                      Long collaborateurId, LocalDate from, LocalDate to,
                                      String search, Pageable pageable) {
        String term = normalise(search);
        return repository.findSettled(mineOnly ? actorId : null, etat, type, collaborateurId,
                from, to, term, searchIds(term), pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> settledCounts(Long actorId, boolean mineOnly) {
        return zeroFilled(repository.countByStateSettled(mineOnly ? actorId : null));
    }

    /**
     * `code -> balanceField` for the live catalogue, as a list of flat maps.
     *
     * A type that tracks no balance reports null rather than being left out, so a caller can
     * tell "draws on nothing" from "unknown code" — the second means a stale client.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> typeBalanceFields() {
        return absenceTypes.findAllLive().stream()
                .map(t -> {
                    Map<String, Object> row = new java.util.HashMap<>();
                    row.put("code", t.getCode());
                    row.put("balanceField", Boolean.TRUE.equals(t.getTracksBalance()) ? t.getBalanceField() : null);
                    return row;
                })
                .toList();
    }

    /** Counts per state for the header tiles, zero-filled so the row is always complete. */
    @Transactional(readOnly = true)
    public Map<String, Long> stateCounts(Long userId) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (DemandeEtat e : DemandeEtat.values()) counts.put(e.name(), 0L);
        for (Object[] row : repository.countByStateForUser(userId)) {
            counts.put(((DemandeEtat) row[0]).name(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    // ═══ Helpers ═════════════════════════════════════════════════════════════

    private LeaveRequest require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ABSENCE_NOT_FOUND));
    }

    private Long collaborateurOf(Long leaveId) {
        return repository.findById(leaveId).map(LeaveRequest::getCollaborateurId).orElse(null);
    }

    private Long paysOf(Long userId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT pays_id FROM Users WHERE id = ?", Long.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * `fullName` for a user id.
     *
     * Returns "" rather than null for an unknown id: the notification templates interpolate
     * this straight into a sentence, where a null prints the word "null" to whoever receives
     * it. Callers that want to test for absence test for blank.
     */
    String nameOf(Long userId) {
        if (userId == null) return "";
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT fullName FROM Users WHERE id = ?", String.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }
}
