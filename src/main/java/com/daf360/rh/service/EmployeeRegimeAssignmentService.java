package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.RegimeAssignmentHistory;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.domain.enums.AssignmentLevel;
import com.daf360.rh.dto.regime.EmployeeRegimeAssignmentDto;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Personal regime assignments (employee_regime_assignments, V127) — the ONLY writer.
 *
 * Window rules, shared with the role assignments (WorkingTimeRegimeService):
 *  · an OPEN-ENDED new assignment from D replaces what was there: rows started before D are
 *    ended on D-1, rows starting on/after D are cancelled;
 *  · a BOUNDED new assignment (a trip, a temporary schedule) is laid on top: nothing is
 *    touched, the resolver takes the newest row covering each date, so the previous regime
 *    comes back by itself after the end date;
 *  · ending a row: started before today → effective_to = yesterday (it stays in the
 *    history); starts today or later → cancelled (is_active = 0, it never applied).
 * "Today" is always the employee's entity's date, never the server's UTC one.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeRegimeAssignmentService {

    private final EmployeeRegimeAssignmentRepository repo;
    private final WorkingTimeRegimeRepository regimeRepo;
    private final RegimeAssignmentHistoryRepository historyRepo;
    private final RegimeResolutionService resolution;
    private final ScheduleCacheNotifier cacheNotifier;

    // ── Writes ────────────────────────────────────────────────────────────────

    public EmployeeRegimeAssignment assign(EmployeeProfile profile, Long regimeId,
                                           LocalDate from, LocalDate to, String reason,
                                           String source, Long actor) {
        WorkingTimeRegime regime = regimeRepo.findById(regimeId).orElseThrow(() ->
                new AppException(ErrorCode.REGIME_NOT_FOUND, "Régime introuvable: id=" + regimeId));
        validateAssignable(regime, profile.getPaysId(), from, to);

        // What applied on the start date before this change — for the history row only.
        WorkingTimeRegime previous = repo.findCovering(profile.getId(), from).stream()
                .map(EmployeeRegimeAssignment::getRegime).findFirst().orElse(null);

        if (to == null) {
            for (EmployeeRegimeAssignment r : repo.findAllActive(profile.getId())) {
                if (!r.getEffectiveFrom().isBefore(from)) {
                    cancel(r, actor);
                } else if (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(from)) {
                    r.setEffectiveTo(from.minusDays(1));
                    r.setEndedBy(actor);
                    r.setEndedAt(OffsetDateTime.now());
                    repo.save(r);
                }
            }
        }

        EmployeeRegimeAssignment saved = repo.save(EmployeeRegimeAssignment.builder()
                .employeeProfileId(profile.getId())
                .regime(regime)
                .effectiveFrom(from)
                .effectiveTo(to)
                .reason(blankToNull(reason))
                .source(source)
                .isActive(true)
                .assignedBy(actor)
                .assignedAt(OffsetDateTime.now())
                .build());

        history(profile.getId(), previous, regime, from, to, reason, actor);
        cacheNotifier.userChanged(profile.getUserId());
        return saved;
    }

    /** Ends (or cancels) one personal assignment. */
    public void end(EmployeeProfile profile, Long assignmentId, Long actor) {
        EmployeeRegimeAssignment a = repo.findById(assignmentId)
                .filter(x -> x.getEmployeeProfileId().equals(profile.getId()))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Affectation de régime introuvable: " + assignmentId));
        LocalDate today = resolution.todayIn(profile.getPaysId());
        if (!Boolean.TRUE.equals(a.getIsActive())
                || (a.getEffectiveTo() != null && a.getEffectiveTo().isBefore(today))) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Cette affectation est déjà terminée ou annulée.");
        }
        endOrCancel(a, today, actor);
        history(profile.getId(), a.getRegime(), null, a.getEffectiveFrom(), a.getEffectiveTo(), null, actor);
        cacheNotifier.userChanged(profile.getUserId());
    }

    /** Ends every current and upcoming personal assignment — back to role / default. */
    public void endAll(EmployeeProfile profile, Long actor) {
        LocalDate today = resolution.todayIn(profile.getPaysId());
        List<EmployeeRegimeAssignment> rows = repo.findCurrentAndUpcoming(profile.getId(), today);
        if (rows.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Cet employé n'a pas de régime personnel en cours ou à venir.");
        }
        for (EmployeeRegimeAssignment a : rows) {
            endOrCancel(a, today, actor);
            history(profile.getId(), a.getRegime(), null, a.getEffectiveFrom(), a.getEffectiveTo(), null, actor);
        }
        cacheNotifier.userChanged(profile.getUserId());
    }

    /**
     * The onboarding wizard's regime step.
     *
     * Before V127 the chosen regime always became a personal assignment, so every new hire
     * was pinned and later changes to their role's assignment or to the entity default never
     * reached them. Now a personal row is written ONLY when RH picked something other than
     * what the employee inherits on the start date. A re-run of an unfinished wizard first
     * cancels the row its previous attempt wrote.
     */
    public void recordOnboardingChoice(EmployeeProfile profile, Long regimeId, LocalDate start, Long actor) {
        if (regimeId == null || start == null) return;
        WorkingTimeRegime regime = regimeRepo.findById(regimeId).orElseThrow(() ->
                new AppException(ErrorCode.REGIME_NOT_FOUND, "Régime introuvable: id=" + regimeId));
        validateAssignable(regime, profile.getPaysId(), start, null);

        for (EmployeeRegimeAssignment old :
                repo.findByEmployeeProfileIdAndSourceAndIsActiveTrue(profile.getId(),
                        EmployeeRegimeAssignment.SOURCE_ONBOARDING)) {
            cancel(old, actor);
        }

        ResolvedRegimeDto inherited = resolution.resolveInherited(profile.getUserId(), profile.getPaysId(), start);
        if (inherited != null && regimeId.equals(inherited.getRegimeId())) {
            log.info("Onboarding regime {} for profile {} is the inherited one ({}) — no personal assignment.",
                    regimeId, profile.getId(), inherited.getAssignmentLevel());
            cacheNotifier.userChanged(profile.getUserId());
            return;
        }
        assign(profile, regimeId, start, null, "Choix à l'embauche",
                EmployeeRegimeAssignment.SOURCE_ONBOARDING, actor);
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EmployeeRegimeAssignmentDto> currentAndUpcoming(EmployeeProfile profile) {
        LocalDate today = resolution.todayIn(profile.getPaysId());
        return repo.findCurrentAndUpcoming(profile.getId(), today).stream()
                .map(a -> toDto(a, today)).toList();
    }

    /** The personal assignment in force today, else the next one to start — for the profile card. */
    @Transactional(readOnly = true)
    public Optional<EmployeeRegimeAssignment> currentOrNext(EmployeeProfile profile) {
        LocalDate today = resolution.todayIn(profile.getPaysId());
        List<EmployeeRegimeAssignment> covering = repo.findCovering(profile.getId(), today);
        if (!covering.isEmpty()) return Optional.of(covering.get(0));
        return repo.findCurrentAndUpcoming(profile.getId(), today).stream()
                .filter(a -> a.getEffectiveFrom().isAfter(today))
                .findFirst();
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    /**
     * Shared by the personal, role and onboarding paths: the regime must exist, be active,
     * belong to the target entity and NOT be a seasonal one (a seasonal regime applies to
     * the whole entity through its own window; assigned personally it used to apply all year).
     */
    public static void validateAssignable(WorkingTimeRegime regime, Long paysId, LocalDate from, LocalDate to) {
        if (!Boolean.TRUE.equals(regime.getIsActive()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Ce régime est désactivé.");
        if (regime.getPaysId() == null || !regime.getPaysId().equals(paysId))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Ce régime n'appartient pas à cette entité.");
        if (regime.getSeasonalFrom() != null)
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Un régime saisonnier s'applique à toute l'entité pendant sa période : il ne peut pas être affecté à un employé ou à un rôle.");
        if (from == null)
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "La date de début est obligatoire.");
        if (to != null && to.isBefore(from))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "La date de fin doit être après la date de début.");
    }

    private void endOrCancel(EmployeeRegimeAssignment a, LocalDate today, Long actor) {
        if (!a.getEffectiveFrom().isBefore(today)) {
            cancel(a, actor);
        } else {
            a.setEffectiveTo(today.minusDays(1));
            a.setEndedBy(actor);
            a.setEndedAt(OffsetDateTime.now());
            repo.save(a);
        }
    }

    private void cancel(EmployeeRegimeAssignment a, Long actor) {
        a.setIsActive(false);
        a.setEndedBy(actor);
        a.setEndedAt(OffsetDateTime.now());
        repo.save(a);
    }

    private void history(Long profileId, WorkingTimeRegime oldR, WorkingTimeRegime newR,
                         LocalDate from, LocalDate to, String reason, Long actor) {
        // changed_by is NOT NULL + FK Users: the old fallback to 0 failed the insert.
        if (actor == null) {
            log.warn("Regime change on profile {} without an identified actor — no history row.", profileId);
            return;
        }
        historyRepo.save(RegimeAssignmentHistory.builder()
                .assignmentLevel(AssignmentLevel.EMPLOYEE).targetId(profileId)
                .oldRegime(oldR).newRegime(newR)
                .effectiveFrom(from).effectiveTo(to)
                .reason(reason).changedBy(actor).changedAt(OffsetDateTime.now()).build());
    }

    private static EmployeeRegimeAssignmentDto toDto(EmployeeRegimeAssignment a, LocalDate today) {
        WorkingTimeRegime r = a.getRegime();
        return EmployeeRegimeAssignmentDto.builder()
                .id(a.getId())
                .regimeId(r.getId())
                .regimeLabelFr(r.getLabelFr())
                .regimeLabelEn(r.getLabelEn())
                .effectiveFrom(a.getEffectiveFrom())
                .effectiveTo(a.getEffectiveTo())
                .reason(a.getReason())
                .source(a.getSource())
                .inForce(a.covers(today))
                .upcoming(a.getEffectiveFrom().isAfter(today))
                .assignedAt(a.getAssignedAt())
                .build();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
