package com.daf360.rh.service;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.Teletravail;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.teletravail.TeletravailCreate;
import com.daf360.rh.dto.teletravail.TeletravailDecision;
import com.daf360.rh.dto.teletravail.TeletravailQuotaDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.LeaveRequestRepository;
import com.daf360.rh.repository.TeletravailRepository;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Télétravail — remote work, one day or half a day per request.
 *
 * WHAT THE TIMESHEET CHECKED, AND WHAT IT DID NOT
 * -----------------------------------------------------------------------------
 * The timesheet's only rule was "balance ≥ 1", and on submission even that never ran (it
 * tested the state before @PrePersist had set it). Two requests on the same day, a request on
 * a Sunday, on a public holiday, in the past, or on a day already taken as congé were all
 * accepted. Every one of those is refused here, and the limits an entity actually wants —
 * which durations, how many days a week or a month, how much notice — are configuration on
 * dbo.pays (V116), edited from Administration, never literals.
 *
 * HALF DAYS
 * -----------------------------------------------------------------------------
 * A request is FULL_DAY, HALF_DAY_MORNING or HALF_DAY_AFTERNOON — the congé categories, so
 * the two desks agree on what a half day is. The entity decides which are offered
 * ({@code tt_allowed_durations}: BOTH, FULL_DAY, HALF_DAY). A morning and an afternoon on the
 * same day are two compatible requests; a half day of télétravail beside a half day of congé
 * on the other half is too. Anything else on the same half is a collision.
 *
 * PENDING COUNTS AGAINST THE CAPS AND THE BALANCE
 * -----------------------------------------------------------------------------
 * Same reasoning as {@link AutorisationService}: a cap that only counts approved requests is
 * walked past by filing five at once. The balance is DEBITED only on approval, but a request
 * is refused at submission when the balance cannot absorb it together with what is already
 * pending — the check the timesheet meant to make.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeletravailService {

    private static final BigDecimal FULL = BigDecimal.ONE;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final TeletravailRepository repository;
    private final LeaveRequestRepository leaveRepository;
    private final LeaveApproverService approvers;
    private final WorkingDayCalculator calendar;
    private final TenantService tenantService;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationRoutingService routing;

    /**
     * The per-entity rules, as V116 stores them on dbo.pays.
     *
     * allowedDurations: BOTH | FULL_DAY | HALF_DAY. Caps are null for no limit.
     */
    public record Rules(String allowedDurations, BigDecimal maxDaysPerWeek, BigDecimal maxDaysPerMonth,
                        Integer noticeDays, boolean usesBalance) {

        static Rules defaults() {
            // What the timesheet did, plus half days: no caps, no notice, the balance is drawn.
            return new Rules("BOTH", null, null, null, true);
        }

        public List<LeaveCategory> allowedCategories() {
            return switch (allowedDurations == null ? "BOTH" : allowedDurations) {
                case "FULL_DAY" -> List.of(LeaveCategory.FULL_DAY);
                case "HALF_DAY" -> List.of(LeaveCategory.HALF_DAY_MORNING, LeaveCategory.HALF_DAY_AFTERNOON);
                default -> List.of(LeaveCategory.FULL_DAY, LeaveCategory.HALF_DAY_MORNING,
                                   LeaveCategory.HALF_DAY_AFTERNOON);
            };
        }
    }

    // ═══ Filing ══════════════════════════════════════════════════════════════

    @Transactional
    public Teletravail create(TeletravailCreate dto, Long actorId) {
        return createFor(dto, actorId, actorId, false);
    }

    /** HR recording a day that already happened — filed already approved, balance debited. */
    @Transactional
    public Teletravail settle(TeletravailCreate dto, Long collaborateurId, Long actorId) {
        return createFor(dto, collaborateurId, actorId, true);
    }

    private Teletravail createFor(TeletravailCreate dto, Long collaborateurId, Long actorId, boolean settle) {
        Long paysId = paysOf(collaborateurId);
        Rules rules = rulesFor(paysId);
        LeaveCategory category = categoryOf(dto, rules);
        BigDecimal jours = joursOf(category);

        if (!settle) {
            assertTiming(dto.getDateTeletravail(), rules, paysId);
        }
        assertWorkingDay(dto.getDateTeletravail(), paysId);
        assertFits(collaborateurId, dto.getDateTeletravail(), category, jours, rules, null);

        Teletravail t = Teletravail.builder()
                .collaborateurId(collaborateurId)
                .responsableId(settle ? actorId : dto.getResponsableId())
                .responsableAdjointId(settle ? null : dto.getResponsableAdjointId())
                .paysId(paysId)
                .dateTeletravail(dto.getDateTeletravail())
                .category(category)
                .jours(jours)
                .etatDemande(settle ? DemandeEtat.VALIDE : DemandeEtat.EN_ATTENTE)
                .dateValidation(settle ? LocalDate.now() : null)
                .decidedBy(settle ? actorId : null)
                .reason(dto.getReason())
                .createdBy(actorId)
                .build();

        if (settle) {
            debit(t, rules);
        } else {
            assertBalanceAbsorbs(collaborateurId, jours, rules, null);
        }
        Teletravail saved = repository.save(t);

        if (!settle) {
            notify("TELETRAVAIL_SUBMITTED", saved, saved.getResponsableId(), Map.of());
            notify("TELETRAVAIL_SUBMITTED", saved, saved.getResponsableAdjointId(), Map.of());
        }
        return saved;
    }

    /**
     * Edit — the owner or an approver, and ONLY while nobody has decided it.
     *
     * Stricter than autorisations on purpose: an approved télétravail has debited a balance,
     * and editing its duration in place would leave the debit and the row disagreeing. A
     * decided request that is wrong is archived (which refunds) and filed again.
     */
    @Transactional
    public Teletravail update(Long id, TeletravailCreate dto, Long actorId, boolean canSettle) {
        Teletravail existing = require(id);

        if (!existing.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être modifiée.");
        }
        boolean isOwner = existing.getCollaborateurId().equals(actorId);
        boolean isApprover = actorId != null
                && (actorId.equals(existing.getResponsableId())
                 || actorId.equals(existing.getResponsableAdjointId()));
        if (!canSettle && !isOwner && !isApprover) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette demande appartient à un autre collaborateur.");
        }

        Rules rules = rulesFor(existing.getPaysId());
        LeaveCategory category = categoryOf(dto, rules);
        BigDecimal jours = joursOf(category);

        if (isOwner) {
            assertTiming(dto.getDateTeletravail(), rules, existing.getPaysId());
        }
        assertWorkingDay(dto.getDateTeletravail(), existing.getPaysId());
        // Excluding this row from its own checks — otherwise an edit always collides with itself.
        assertFits(existing.getCollaborateurId(), dto.getDateTeletravail(), category, jours, rules, id);
        assertBalanceAbsorbs(existing.getCollaborateurId(), jours, rules, id);

        existing.setDateTeletravail(dto.getDateTeletravail());
        existing.setCategory(category);
        existing.setJours(jours);
        existing.setReason(dto.getReason());
        if (dto.getResponsableId() != null) existing.setResponsableId(dto.getResponsableId());
        existing.setResponsableAdjointId(dto.getResponsableAdjointId());
        return repository.save(existing);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    @Transactional
    public Teletravail decide(Long id, TeletravailDecision decision, Long actorId, boolean canSettle) {
        Teletravail t = require(id);

        // The timesheet re-approved a decided request and debited the balance a second time.
        if (!t.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS, "Cette demande a déjà été traitée.");
        }
        if (!canSettle && !approvers.canDecide(actorId, t.getResponsableId(), t.getResponsableAdjointId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous n'êtes pas responsable de cette demande.");
        }

        if (Boolean.TRUE.equals(decision.getApproved())) {
            Rules rules = rulesFor(t.getPaysId());
            // Re-checked at approval: another request may have filled the week, or the day, in
            // between — and this is the moment the balance is spent.
            assertFits(t.getCollaborateurId(), t.getDateTeletravail(), t.getCategory(), t.getJours(), rules, t.getId());
            debit(t, rules);
            t.setEtatDemande(DemandeEtat.VALIDE);
            t.setMotifRefus(null);
        } else {
            if (decision.getMotifRefus() == null || decision.getMotifRefus().isBlank()) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un motif est obligatoire pour refuser une demande.");
            }
            t.setEtatDemande(DemandeEtat.REFUSE);
            t.setMotifRefus(decision.getMotifRefus().trim());
        }
        t.setDateValidation(LocalDate.now());
        t.setDecidedBy(actorId);

        Teletravail saved = repository.save(t);
        notify("TELETRAVAIL_DECIDED", saved, saved.getCollaborateurId(), Map.of(
                "decision", Boolean.TRUE.equals(decision.getApproved()) ? "validée" : "refusée",
                "motifRefus", saved.getMotifRefus() == null ? "" : "Motif : " + saved.getMotifRefus()));
        return saved;
    }

    /** The employee takes back their own pending request. Nothing was debited, nothing moves. */
    @Transactional
    public Teletravail withdraw(Long id, String motif, Long actorId) {
        Teletravail t = require(id);

        if (!t.getCollaborateurId().equals(actorId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette demande appartient à un autre collaborateur.");
        }
        if (!t.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être retirée.");
        }
        if (motif == null || motif.isBlank()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, "Un motif est requis pour retirer une demande.");
        }

        t.setEtatDemande(DemandeEtat.ARCHIVE);
        t.setMotifAnnulation(motif.trim());
        t.setDecidedBy(actorId);
        Teletravail saved = repository.save(t);

        notify("TELETRAVAIL_WITHDRAWN", saved, saved.getResponsableId(), Map.of("motif", motif.trim()));
        notify("TELETRAVAIL_WITHDRAWN", saved, saved.getResponsableAdjointId(), Map.of("motif", motif.trim()));
        return saved;
    }

    /**
     * Archive — HR's removal. Refunds what was debited, and only that.
     *
     * Unlike the timesheet, it does NOT wipe the refusal motive: the record keeps why a request
     * was refused even after it is archived.
     */
    @Transactional
    public Teletravail archive(Long id, Long actorId) {
        Teletravail t = require(id);
        if (t.getEtatDemande() == DemandeEtat.ARCHIVE) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS, "Cette demande est déjà archivée.");
        }
        if (t.isBalanceDebited()) {
            addToBalance(t.getCollaborateurId(), t.getJours());
            t.setBalanceDebited(false);
        }
        t.setEtatDemande(DemandeEtat.ARCHIVE);
        t.setDecidedBy(actorId);
        return repository.save(t);
    }

    // ═══ The rules ═══════════════════════════════════════════════════════════

    /** ONE reader, used by the checks and by the form — see the class comment. */
    public Rules rulesFor(Long paysId) {
        if (paysId == null) return Rules.defaults();
        List<Rules> rows = jdbcTemplate.query("""
                SELECT tt_allowed_durations, tt_max_days_per_week, tt_max_days_per_month,
                       tt_notice_days, tt_uses_balance
                FROM   pays WHERE id = ?
                """,
                (rs, n) -> new Rules(
                        rs.getString("tt_allowed_durations") == null ? "BOTH" : rs.getString("tt_allowed_durations"),
                        rs.getBigDecimal("tt_max_days_per_week"),
                        rs.getBigDecimal("tt_max_days_per_month"),
                        (Integer) rs.getObject("tt_notice_days"),
                        rs.getObject("tt_uses_balance") == null || rs.getBoolean("tt_uses_balance")),
                paysId);
        return rows.isEmpty() ? Rules.defaults() : rows.get(0);
    }

    /** What the form shows: the rules, the balance, and the usage around the picked day. */
    @Transactional(readOnly = true)
    public TeletravailQuotaDto quotaOf(Long userId, LocalDate reference) {
        Long paysId = paysOf(userId);
        Rules r = rulesFor(paysId);
        LocalDate ref = reference == null ? LocalDate.now() : reference;

        LocalDate weekStart = ref.with(DayOfWeek.MONDAY);
        LocalDate monthStart = ref.withDayOfMonth(1);

        return TeletravailQuotaDto.builder()
                .allowedCategories(r.allowedCategories().stream().map(Enum::name).toList())
                .maxDaysPerWeek(r.maxDaysPerWeek())
                .maxDaysPerMonth(r.maxDaysPerMonth())
                .noticeDays(r.noticeDays())
                .usesBalance(r.usesBalance())
                .balance(balanceOf(userId))
                .pendingDays(repository.pendingDays(userId, null))
                .referenceDate(ref)
                .weekUsedDays(repository.daysBetween(userId, weekStart, weekStart.plusWeeks(1), null))
                .monthUsedDays(repository.daysBetween(userId, monthStart, monthStart.plusMonths(1), null))
                .build();
    }

    /**
     * Days the form should grey out: those already fully taken by télétravail. A day holding
     * only a morning stays open — the afternoon is still free.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> committedFrom(Long userId, LocalDate from) {
        return repository.findCommittedFrom(userId, from).stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "date", t.getDateTeletravail().toString(),
                        "category", t.getCategory().name()))
                .toList();
    }

    private LeaveCategory categoryOf(TeletravailCreate dto, Rules rules) {
        LeaveCategory c = dto.getCategory() == null ? LeaveCategory.FULL_DAY : dto.getCategory();
        if (c == LeaveCategory.MULTIPLE_DAYS) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une demande de télétravail porte sur une seule journée ou demi-journée.");
        }
        if (!rules.allowedCategories().contains(c)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, switch (rules.allowedDurations()) {
                case "FULL_DAY" -> "Votre entité n'autorise que des journées entières de télétravail.";
                case "HALF_DAY" -> "Votre entité n'autorise que des demi-journées de télétravail.";
                default -> "Cette durée n'est pas autorisée par votre entité.";
            });
        }
        return c;
    }

    private BigDecimal joursOf(LeaveCategory c) {
        return c == LeaveCategory.FULL_DAY ? FULL : HALF;
    }

    /** Not in the past, and the entity's notice in WORKING days — the congés' convention. */
    private void assertTiming(LocalDate date, Rules r, Long paysId) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une demande de télétravail ne peut pas porter sur une date passée.");
        }
        if (r.noticeDays() != null && r.noticeDays() > 0) {
            LocalDate earliest = calendar.addWorkingDays(today, r.noticeDays(), paysId);
            if (date.isBefore(earliest)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Le télétravail doit être demandé " + r.noticeDays()
                        + " jour(s) ouvré(s) à l'avance, soit à partir du " + earliest + ".");
            }
        }
    }

    /** Remote work on a rest day or a public holiday is not remote work. */
    private void assertWorkingDay(LocalDate date, Long paysId) {
        if (!calendar.isWorkingDay(date, paysId)) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Le " + date + " n'est pas un jour ouvré pour votre entité (week-end ou jour férié).");
        }
    }

    /**
     * Collisions first, then the caps — the most specific refusal wins, so somebody who has
     * already asked for that morning is told THAT, rather than about their weekly total.
     */
    private void assertFits(Long userId, LocalDate date, LeaveCategory category, BigDecimal jours,
                            Rules r, Long excludeId) {
        boolean morning = category != LeaveCategory.HALF_DAY_AFTERNOON;
        boolean afternoon = category != LeaveCategory.HALF_DAY_MORNING;

        // 1. Another télétravail on the same half.
        for (Teletravail other : repository.findCommittedOnDay(userId, date, excludeId)) {
            if ((morning && other.coversMorning()) || (afternoon && other.coversAfternoon())) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Une demande de télétravail couvre déjà tout ou partie de cette journée.");
            }
        }

        // 2. A congé on the same half — absent and remote at once is not a thing.
        for (LeaveRequest l : leaveRepository.findBlockingRanges(userId, date)) {
            if (l.getDateDebut() == null || l.getDateDebut().isAfter(date)) continue;
            LeaveCategory lc = l.getCategory();
            boolean lMorning = lc != LeaveCategory.HALF_DAY_AFTERNOON;
            boolean lAfternoon = lc != LeaveCategory.HALF_DAY_MORNING;
            if ((morning && lMorning) || (afternoon && lAfternoon)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un congé couvre déjà tout ou partie de cette journée.");
            }
        }

        // 3. The caps.
        if (r.maxDaysPerWeek() != null) {
            LocalDate weekStart = date.with(DayOfWeek.MONDAY);
            BigDecimal used = repository.daysBetween(userId, weekStart, weekStart.plusWeeks(1), excludeId);
            if (used.add(jours).compareTo(r.maxDaysPerWeek()) > 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Cette demande dépasse le maximum de " + plain(r.maxDaysPerWeek())
                        + " jour(s) de télétravail par semaine (déjà demandés : " + plain(used) + ").");
            }
        }
        if (r.maxDaysPerMonth() != null) {
            LocalDate monthStart = date.withDayOfMonth(1);
            BigDecimal used = repository.daysBetween(userId, monthStart, monthStart.plusMonths(1), excludeId);
            if (used.add(jours).compareTo(r.maxDaysPerMonth()) > 0) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Cette demande dépasse le maximum de " + plain(r.maxDaysPerMonth())
                        + " jour(s) de télétravail par mois (déjà demandés : " + plain(used) + ").");
            }
        }
    }

    // ═══ The balance ═════════════════════════════════════════════════════════

    /** At submission: this request plus everything still pending must fit in the balance. */
    private void assertBalanceAbsorbs(Long userId, BigDecimal jours, Rules r, Long excludeId) {
        if (!r.usesBalance()) return;
        BigDecimal balance = BigDecimal.valueOf(nz(balanceOf(userId)));
        BigDecimal pending = repository.pendingDays(userId, excludeId);
        if (balance.subtract(pending).compareTo(jours) < 0) {
            throw new AppException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                    "Solde de télétravail insuffisant : " + plain(balance) + " jour(s) disponible(s), "
                    + plain(pending) + " déjà en attente.");
        }
    }

    /** At approval or régularisation: re-checked against the stored balance, then written. */
    private void debit(Teletravail t, Rules r) {
        if (!r.usesBalance()) return;
        BigDecimal balance = BigDecimal.valueOf(nz(balanceOf(t.getCollaborateurId())));
        if (balance.compareTo(t.getJours()) < 0) {
            throw new AppException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                    "Solde de télétravail insuffisant : " + plain(balance) + " jour(s) disponible(s).");
        }
        addToBalance(t.getCollaborateurId(), t.getJours().negate());
        t.setBalanceDebited(true);
    }

    /** COALESCE in the UPDATE: adding to a NULL balance yields NULL in SQL, erasing the column. */
    private void addToBalance(Long userId, BigDecimal delta) {
        int rows = jdbcTemplate.update(
                "UPDATE Users SET soldeTeletravail = COALESCE(soldeTeletravail, 0) + ? WHERE id = ?",
                delta.doubleValue(), userId);
        if (rows == 0) {
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "Collaborateur " + userId + " introuvable pour la mise à jour du solde.");
        }
    }

    private Double balanceOf(Long userId) {
        List<Double> rows = jdbcTemplate.query(
                "SELECT soldeTeletravail FROM Users WHERE id = ?",
                (rs, n) -> (Double) rs.getObject("soldeTeletravail"), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static double nz(Double d) {
        return d == null ? 0d : d;
    }

    /** "2" rather than "2.0", "0.5" stays "0.5" — for messages a person reads. */
    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    // ═══ The queues ══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<Teletravail> mine(Long userId, Pageable p) {
        return repository.findByCollaborateurId(userId, p);
    }

    @Transactional(readOnly = true)
    public Page<Teletravail> managerQueue(Long managerId, DemandeEtat etat, Pageable p) {
        return repository.findForManager(managerId, etat, p);
    }

    @Transactional(readOnly = true)
    public Page<Teletravail> team(Long managerId, DemandeEtat etat, Pageable p) {
        List<Long> ids = approvers.subordinateUserIds(managerId);
        return ids.isEmpty() ? Page.empty(p) : repository.findForEmployees(ids, etat, p);
    }

    @Transactional(readOnly = true)
    public Page<Teletravail> global(DemandeEtat etat, LocalDate from, LocalDate to, Pageable p) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        return repository.findGlobal(scope.all() ? 1 : 0, scopeIds(scope), etat, from, to, p);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts(Long userId, String scopeKind) {
        List<Object[]> rows = switch (scopeKind) {
            case "manager" -> repository.countByStateForManager(userId);
            case "global"  -> {
                PaysScopeContext.Scope s = tenantService.getPaysScope();
                yield repository.countByStateGlobal(s.all() ? 1 : 0, scopeIds(s));
            }
            default -> repository.countByStateForUser(userId);
        };
        Map<String, Long> out = new HashMap<>();
        for (DemandeEtat e : DemandeEtat.values()) out.put(e.name(), 0L);
        long total = 0;
        for (Object[] row : rows) {
            long n = ((Number) row[1]).longValue();
            out.put(((DemandeEtat) row[0]).name(), n);
            total += n;
        }
        out.put("TOTAL", total);
        return out;
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    private List<Long> scopeIds(PaysScopeContext.Scope scope) {
        if (scope.all() || scope.paysIds().isEmpty()) return List.of(-1L);
        return new ArrayList<>(scope.paysIds());
    }

    private Long paysOf(Long userId) {
        List<Long> rows = jdbcTemplate.queryForList("SELECT pays_id FROM Users WHERE id = ?", Long.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String nameOf(Long userId) {
        if (userId == null) return "";
        List<String> rows = jdbcTemplate.queryForList("SELECT fullName FROM Users WHERE id = ?", String.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private Teletravail require(Long id) {
        return repository.findById(id).orElseThrow(() -> new AppException(ErrorCode.TELETRAVAIL_NOT_FOUND));
    }

    /** One event through the routing engine. Failure is logged, never rethrown. */
    private void notify(String eventCode, Teletravail t, Long subjectUserId, Map<String, String> extra) {
        if (subjectUserId == null) return;
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("employee", nameOf(t.getCollaborateurId()));
            vars.put("date", String.valueOf(t.getDateTeletravail()));
            vars.put("duree", t.getCategory().getLabelFr().toLowerCase());
            vars.putAll(extra);

            routing.resolveAndDispatch(RoutingContext.builder()
                    .eventCode(eventCode)
                    .paysId(t.getPaysId())
                    .subjectUserId(subjectUserId)
                    .entityType(NotificationEntityType.TELETRAVAIL)
                    .entityId(t.getId())
                    .templateVars(vars)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Télétravail {} : notification {} non envoyée — {}", t.getId(), eventCode, e.toString());
        }
    }
}
