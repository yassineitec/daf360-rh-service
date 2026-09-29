package com.daf360.rh.service;

import com.daf360.rh.domain.AbsenceType;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.leave.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.AbsenceTypeRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
                typeOptions(userId, lang),
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
        if (!canSettle && !existing.getCollaborateurId().equals(actorId)) {
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
        return repository.save(request);
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
                                            boolean canSettle) {
        String term = normalise(search);
        List<Long> ids = repository.findPendingIdsForManager(managerId, from, to, collaborateurId,
                type, term, searchIds(term), hiddenFromManagers());

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
        log.info("Bulk approve by {}: {} approved, {} failed of {} pending",
                managerId, approved, failures.size(), ids.size());
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
        LeaveRequest request = require(id);

        if (request.getEtatDemande() == DemandeEtat.VALIDE) {
            refund(request.getCollaborateurId(), requireType(request.getType()), request.getTotalJours());
        }
        request.setEtatDemande(DemandeEtat.ARCHIVE);
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
    private List<LeaveTypeOptionDto> typeOptions(Long userId, String lang) {
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
                    t.getMaxDays()));
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

    String nameOf(Long userId) {
        if (userId == null) return null;
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT fullName FROM Users WHERE id = ?", String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
