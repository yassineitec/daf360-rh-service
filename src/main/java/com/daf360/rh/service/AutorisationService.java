package com.daf360.rh.service;

import com.daf360.rh.domain.Autorisation;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.autorisation.AutorisationCreate;
import com.daf360.rh.dto.autorisation.AutorisationDecision;
import com.daf360.rh.dto.autorisation.AutorisationQuotaDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.AutorisationRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Autorisations — short paid absences, in minutes.
 *
 * THE QUOTAS ARE CONFIGURATION, NOT CONSTANTS
 * -----------------------------------------------------------------------------
 * The timesheet hardcoded 120 minutes a day, 240 a month and one approved per day — and
 * wrote 240 twice, once in the refusal and once in the "remaining" figure the form shows.
 * Both now come from {@link #rulesFor}, reading the entity's row, so the number the employee
 * is shown and the number they are refused against cannot disagree.
 *
 * A NULL CAP MEANS NO LIMIT, not zero. An entity that does not cap autorisations is a real
 * configuration; one capped at zero minutes would be a mistake nobody could distinguish from
 * an unset value.
 *
 * PENDING COUNTS AGAINST THE QUOTA
 * -----------------------------------------------------------------------------
 * Every check counts EN_ATTENTE alongside VALIDE. A pending request has not been granted, but
 * excluding it lets somebody file five that each pass in isolation and walk straight past a
 * monthly cap — which is what the timesheet allowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutorisationService {

    private final AutorisationRepository repository;
    private final LeaveApproverService approvers;
    private final TenantService tenantService;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationRoutingService routing;

    /** The per-entity rules, as V114 stores them on dbo.pays. Null = no limit. */
    public record Rules(Integer dailyCapMinutes, Integer monthlyCapMinutes, Integer maxPerDay,
                        Integer noticeDays, Boolean requiresJustification) {}

    // ═══ Filing ══════════════════════════════════════════════════════════════

    @Transactional
    public Autorisation create(AutorisationCreate dto, Long actorId) {
        return createFor(dto, actorId, actorId, false);
    }

    /** HR filing on somebody's behalf — already approved, like a congé régularisation. */
    @Transactional
    public Autorisation settle(AutorisationCreate dto, Long collaborateurId, Long actorId) {
        return createFor(dto, collaborateurId, actorId, true);
    }

    private Autorisation createFor(AutorisationCreate dto, Long collaborateurId, Long actorId,
                                   boolean settle) {
        Long paysId = paysOf(collaborateurId);
        int minutes = durationOf(dto);
        Rules rules = rulesFor(paysId);

        if (!settle) {
            assertNotice(dto.getDateAutorisation(), rules);
        }
        assertFits(collaborateurId, dto, minutes, rules, null);

        Autorisation saved = repository.save(Autorisation.builder()
                .collaborateurId(collaborateurId)
                .responsableId(dto.getResponsableId())
                .responsableAdjointId(dto.getResponsableAdjointId())
                .paysId(paysId)
                .dateAutorisation(dto.getDateAutorisation())
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .dureeMinutes(minutes)
                // A régularisation is already decided — HR is recording something that
                // happened, not asking anyone.
                .etatDemande(settle ? DemandeEtat.VALIDE : DemandeEtat.EN_ATTENTE)
                .dateValidation(settle ? LocalDate.now() : null)
                .decidedBy(settle ? actorId : null)
                .reason(dto.getReason())
                .createdBy(actorId)
                .build());

        if (!settle) {
            notify("AUTORISATION_SUBMITTED", saved, saved.getResponsableId(), Map.of());
            notify("AUTORISATION_SUBMITTED", saved, saved.getResponsableAdjointId(), Map.of());
        }
        return saved;
    }

    /** Edit — the owner or an approver, and only while nobody has decided it. */
    @Transactional
    public Autorisation update(Long id, AutorisationCreate dto, Long actorId, boolean canSettle) {
        Autorisation existing = require(id);

        if (!existing.isPending() && !canSettle) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être modifiée.");
        }
        boolean isOwner = existing.getCollaborateurId().equals(actorId);
        boolean isApprover = actorId != null
                && (actorId.equals(existing.getResponsableId())
                 || actorId.equals(existing.getResponsableAdjointId()));
        if (!canSettle && !isOwner && !isApprover) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Cette demande appartient à un autre collaborateur.");
        }

        int minutes = durationOf(dto);
        // Excluding this row from its own quota check — without it an edit always collides
        // with itself and nothing can ever be moved by five minutes.
        assertFits(existing.getCollaborateurId(), dto, minutes, rulesFor(existing.getPaysId()), id);

        existing.setDateAutorisation(dto.getDateAutorisation());
        existing.setStartTime(dto.getStartTime());
        existing.setEndTime(dto.getEndTime());
        existing.setDureeMinutes(minutes);
        existing.setReason(dto.getReason());
        if (dto.getResponsableId() != null) existing.setResponsableId(dto.getResponsableId());
        existing.setResponsableAdjointId(dto.getResponsableAdjointId());
        return repository.save(existing);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    @Transactional
    public Autorisation decide(Long id, AutorisationDecision decision, Long actorId, boolean canSettle) {
        Autorisation a = require(id);

        if (!a.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS, "Cette demande a déjà été traitée.");
        }
        if (!canSettle && !approvers.canDecide(actorId, a.getResponsableId(), a.getResponsableAdjointId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous n'êtes pas responsable de cette demande.");
        }

        if (Boolean.TRUE.equals(decision.getApproved())) {
            // Re-checked at approval, not only at submission: the quota may have been filled
            // by another request approved in between, and this is the moment it is spent.
            assertFitsOnApproval(a);
            a.setEtatDemande(DemandeEtat.VALIDE);
            a.setMotifRefus(null);
        } else {
            if (decision.getMotifRefus() == null || decision.getMotifRefus().isBlank()) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un motif est obligatoire pour refuser une demande.");
            }
            a.setEtatDemande(DemandeEtat.REFUSE);
            a.setMotifRefus(decision.getMotifRefus());
        }
        a.setDateValidation(LocalDate.now());
        a.setDecidedBy(actorId);

        Autorisation saved = repository.save(a);
        notify("AUTORISATION_DECIDED", saved, saved.getCollaborateurId(), Map.of(
                "decision", Boolean.TRUE.equals(decision.getApproved()) ? "validée" : "refusée",
                "motifRefus", saved.getMotifRefus() == null ? "" : "Motif : " + saved.getMotifRefus()));
        return saved;
    }

    /**
     * The employee takes back their own pending request.
     *
     * No quota to release: a withdrawn request simply stops counting, because the checks read
     * the table rather than a running total.
     */
    @Transactional
    public Autorisation withdraw(Long id, String motif, Long actorId) {
        Autorisation a = require(id);

        if (!a.getCollaborateurId().equals(actorId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette demande appartient à un autre collaborateur.");
        }
        if (!a.isPending()) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Seule une demande en attente peut être retirée.");
        }
        if (motif == null || motif.isBlank()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION, "Un motif est requis pour retirer une demande.");
        }

        a.setEtatDemande(DemandeEtat.ARCHIVE);
        a.setMotifAnnulation(motif.trim());
        a.setDecidedBy(actorId);
        Autorisation saved = repository.save(a);

        notify("AUTORISATION_WITHDRAWN", saved, saved.getResponsableId(), Map.of("motif", motif.trim()));
        notify("AUTORISATION_WITHDRAWN", saved, saved.getResponsableAdjointId(), Map.of("motif", motif.trim()));
        return saved;
    }

    /** Archive — HR's removal. Nothing is hard-deleted; an autorisation stays in the record. */
    @Transactional
    public Autorisation archive(Long id, Long actorId) {
        Autorisation a = require(id);
        a.setEtatDemande(DemandeEtat.ARCHIVE);
        a.setDecidedBy(actorId);
        return repository.save(a);
    }

    // ═══ The rules ═══════════════════════════════════════════════════════════

    /**
     * The entity's configured limits. ONE reader, used by the check and by the gauge — see the
     * class comment for why that matters.
     */
    public Rules rulesFor(Long paysId) {
        if (paysId == null) {
            return new Rules(null, null, null, null, Boolean.FALSE);
        }
        List<Rules> rows = jdbcTemplate.query("""
                SELECT autorisation_daily_minutes, autorisation_monthly_minutes,
                       autorisation_max_per_day, autorisation_notice_days,
                       autorisation_requires_justif
                FROM   pays WHERE id = ?
                """,
                (rs, n) -> new Rules(
                        (Integer) rs.getObject("autorisation_daily_minutes"),
                        (Integer) rs.getObject("autorisation_monthly_minutes"),
                        (Integer) rs.getObject("autorisation_max_per_day"),
                        (Integer) rs.getObject("autorisation_notice_days"),
                        rs.getObject("autorisation_requires_justif") == null
                                ? Boolean.FALSE : rs.getBoolean("autorisation_requires_justif")),
                paysId);
        return rows.isEmpty() ? new Rules(null, null, null, null, Boolean.FALSE) : rows.get(0);
    }

    /** What the form shows: the rules, and what is left of the monthly cap. */
    @Transactional(readOnly = true)
    public AutorisationQuotaDto quotaOf(Long userId) {
        Long paysId = paysOf(userId);
        Rules r = rulesFor(paysId);
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        long used = repository.minutesInMonth(userId, monthStart, monthStart.plusMonths(1), null);

        return AutorisationQuotaDto.builder()
                .dailyCapMinutes(r.dailyCapMinutes())
                .monthlyCapMinutes(r.monthlyCapMinutes())
                .maxPerDay(r.maxPerDay())
                .noticeDays(r.noticeDays())
                .requiresJustification(r.requiresJustification())
                .monthlyUsedMinutes(used)
                .monthlyRemainingMinutes(r.monthlyCapMinutes() == null
                        ? null
                        : Math.max(0, r.monthlyCapMinutes() - (int) used))
                .build();
    }

    /** Minutes between start and end. The CHECK in V114 backs this up at the table. */
    private int durationOf(AutorisationCreate dto) {
        if (dto.getEndTime() == null || dto.getStartTime() == null
                || !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "L'heure de fin doit être postérieure à l'heure de début.");
        }
        return (int) Duration.between(dto.getStartTime(), dto.getEndTime()).toMinutes();
    }

    private void assertNotice(LocalDate date, Rules r) {
        if (r.noticeDays() == null || r.noticeDays() <= 0) {
            return;
        }
        if (date.isBefore(LocalDate.now().plusDays(r.noticeDays()))) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une autorisation doit être demandée " + r.noticeDays() + " jour(s) à l'avance.");
        }
    }

    /**
     * The three caps, each skipped when the entity leaves it unset.
     *
     * Order matters for the message: the most specific refusal first, so somebody who already
     * has an autorisation that day is told THAT, rather than being told about minutes.
     */
    private void assertFits(Long userId, AutorisationCreate dto, int minutes, Rules r, Long excludeId) {
        LocalDate date = dto.getDateAutorisation();

        // ONE read for all three per-day checks — see findCommittedOnDay for why the overlap
        // test cannot be expressed in SQL here.
        List<Autorisation> sameDay = repository.findCommittedOnDay(userId, date, excludeId);

        /*
         * Overlap, not equality: the timesheet compared the exact (date, start, end) triple,
         * so 09:00–11:00 and 10:00–12:00 on the same morning were both accepted and the
         * employee was recorded absent twice over the same hour.
         *
         * Rows with no times are SKIPPED rather than treated as all-day: the 130 rows carried
         * over from the timesheet have a date, a state and a duration but no slot — their
         * start/end were never copied into DAF360_HR. Treating a missing slot as a collision
         * would block every new request on a day somebody once took time off.
         */
        boolean overlaps = sameDay.stream().anyMatch(a ->
                a.getStartTime() != null && a.getEndTime() != null
                && a.getStartTime().isBefore(dto.getEndTime())
                && a.getEndTime().isAfter(dto.getStartTime()));
        if (overlaps) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Une autorisation couvre déjà tout ou partie de ce créneau.");
        }
        if (r.maxPerDay() != null && sameDay.size() >= r.maxPerDay()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Vous avez déjà atteint le nombre d'autorisations autorisé pour cette journée.");
        }
        // Null durations count as zero, not as a reason to fail — same carried-over rows.
        long dailyUsed = sameDay.stream()
                .mapToLong(a -> a.getDureeMinutes() == null ? 0L : a.getDureeMinutes())
                .sum();
        if (r.dailyCapMinutes() != null && dailyUsed + minutes > r.dailyCapMinutes()) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Cette demande dépasse le maximum de " + r.dailyCapMinutes() + " minutes par jour.");
        }
        if (r.monthlyCapMinutes() != null) {
            LocalDate monthStart = date.withDayOfMonth(1);
            long monthly = repository.minutesInMonth(userId, monthStart, monthStart.plusMonths(1), excludeId);
            if (monthly + minutes > r.monthlyCapMinutes()) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Cette demande dépasse le maximum de " + r.monthlyCapMinutes() + " minutes par mois.");
            }
        }
    }

    /** The same caps at approval, re-read from the row rather than from a request body. */
    private void assertFitsOnApproval(Autorisation a) {
        AutorisationCreate asDto = new AutorisationCreate();
        asDto.setDateAutorisation(a.getDateAutorisation());
        asDto.setStartTime(a.getStartTime());
        asDto.setEndTime(a.getEndTime());
        assertFits(a.getCollaborateurId(), asDto, a.getDureeMinutes(),
                rulesFor(a.getPaysId()), a.getId());
    }

    // ═══ The queues ══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<Autorisation> mine(Long userId, Pageable p) {
        return repository.findByCollaborateurId(userId, p);
    }

    @Transactional(readOnly = true)
    public Page<Autorisation> managerQueue(Long managerId, DemandeEtat etat, Pageable p) {
        return repository.findForManager(managerId, etat, p);
    }

    @Transactional(readOnly = true)
    public Page<Autorisation> team(Long managerId, DemandeEtat etat, Pageable p) {
        List<Long> ids = approvers.subordinateUserIds(managerId);
        return ids.isEmpty() ? Page.empty(p) : repository.findForEmployees(ids, etat, p);
    }

    @Transactional(readOnly = true)
    public Page<Autorisation> global(DemandeEtat etat, LocalDate from, LocalDate to, Pageable p) {
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
        return zeroFilled(rows);
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    /** Every state present, so a KPI tile shows 0 rather than nothing. */
    private Map<String, Long> zeroFilled(List<Object[]> rows) {
        Map<String, Long> out = new HashMap<>();
        for (DemandeEtat e : DemandeEtat.values()) out.put(e.name(), 0L);
        long total = 0;
        for (Object[] r : rows) {
            long n = ((Number) r[1]).longValue();
            out.put(((DemandeEtat) r[0]).name(), n);
            total += n;
        }
        out.put("TOTAL", total);
        return out;
    }

    private List<Long> scopeIds(PaysScopeContext.Scope scope) {
        if (scope.all() || scope.paysIds().isEmpty()) return List.of(-1L);
        return new ArrayList<>(scope.paysIds());
    }

    private Long paysOf(Long userId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT pays_id FROM Users WHERE id = ?", Long.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    String nameOf(Long userId) {
        if (userId == null) return "";
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT fullName FROM Users WHERE id = ?", String.class, userId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private Autorisation require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.AUTORISATION_NOT_FOUND));
    }

    /**
     * Fires one event through the admin-configurable routing engine — same contract as the
     * congés. Failure is logged, never rethrown: a routing problem must not roll back a
     * decision that has already been taken.
     */
    private void notify(String eventCode, Autorisation a, Long subjectUserId, Map<String, String> extra) {
        if (subjectUserId == null) return;
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("employee", nameOf(a.getCollaborateurId()));
            vars.put("date", String.valueOf(a.getDateAutorisation()));
            vars.put("creneau", a.getStartTime() + " – " + a.getEndTime());
            vars.put("minutes", String.valueOf(a.getDureeMinutes()));
            vars.putAll(extra);

            routing.resolveAndDispatch(RoutingContext.builder()
                    .eventCode(eventCode)
                    .paysId(a.getPaysId())
                    .subjectUserId(subjectUserId)
                    .entityType(NotificationEntityType.AUTORISATION)
                    .entityId(a.getId())
                    .templateVars(vars)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Autorisation {} : notification {} non envoyée — {}", a.getId(), eventCode, e.toString());
        }
    }
}
