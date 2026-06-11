package com.daf360.rh.service;

import com.daf360.rh.domain.LeaveBalance;
import com.daf360.rh.domain.enums.LeaveType;
import com.daf360.rh.dto.leave.AdjustBalanceDto;
import com.daf360.rh.dto.leave.LeaveBalanceResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.LeaveBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Manages leave_balances rows.
 * Works exclusively with [leave_balances] — does NOT touch absences/teletravails.
 *
 * Table verified 2026-05-31: id, employee_profile_id, annee, leave_type,
 * jours_acquis numeric(6,2), jours_pris numeric(6,2), jours_restants numeric(7,2) NULLABLE,
 * derniere_maj datetimeoffset NOT NULL.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class LeaveBalanceService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /**
     * Default annual allocation per leave type (in working days).
     * HR Managers may override per employee via adjustBalance().
     */
    public static final Map<LeaveType, BigDecimal> DEFAULT_DAYS = Map.of(
            LeaveType.CONGE,        new BigDecimal("30"),
            LeaveType.MALADIE,      BigDecimal.ZERO,          // no fixed quota; adjusted when issued
            LeaveType.MATERNITE,    new BigDecimal("98"),
            LeaveType.PATERNITE,    new BigDecimal("10"),
            LeaveType.EXCEPTIONNEL, new BigDecimal("6"),
            LeaveType.DEUIL_AUTRE,  new BigDecimal("3")
    );

    private final LeaveBalanceRepository balanceRepo;
    private final AuditService           auditService;

    // ── Init ──────────────────────────────────────────────────────────────────

    /**
     * Creates one leave_balance row per LeaveType for the given year.
     * Idempotent — skips types that already have a balance record.
     * Called automatically when an employee's profile transitions to ACTIVE.
     */
    public void initializeBalances(Long employeeProfileId, Integer annee) {
        for (LeaveType type : LeaveType.values()) {
            if (balanceRepo.existsByEmployeeProfileIdAndAnneeAndLeaveType(
                    employeeProfileId, annee, type.name())) {
                continue;
            }
            BigDecimal quota = DEFAULT_DAYS.getOrDefault(type, BigDecimal.ZERO);
            LeaveBalance lb = LeaveBalance.builder()
                    .employeeProfileId(employeeProfileId)
                    .annee(annee)
                    .leaveType(type.name())
                    .joursAcquis(quota)
                    .joursPris(BigDecimal.ZERO)
                    .joursRestants(quota)
                    .derniereMaj(OffsetDateTime.now(PARIS))
                    .build();
            balanceRepo.save(lb);
        }
        log.info("Initialized {} leave balances for profileId={} annee={}", LeaveType.values().length, employeeProfileId, annee);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LeaveBalanceResponseDto> getBalances(Long employeeProfileId, Integer annee) {
        return balanceRepo.findByEmployeeProfileIdAndAnnee(employeeProfileId, annee)
                .stream().map(this::toDto).toList();
    }

    // ── HR adjustment ─────────────────────────────────────────────────────────

    /**
     * HR Manager adds/removes days from a balance.
     * Positive delta → more days available; negative → reduction.
     */
    public LeaveBalanceResponseDto adjustBalance(Long balanceId, AdjustBalanceDto dto,
                                                  Authentication auth) {
        LeaveBalance balance = findOrThrow(balanceId);

        BigDecimal oldAcquis    = balance.getJoursAcquis();
        BigDecimal oldRestants  = balance.getJoursRestants() != null
                                    ? balance.getJoursRestants()
                                    : balance.getJoursAcquis().subtract(balance.getJoursPris());

        BigDecimal delta = dto.getDelta();
        balance.setJoursAcquis(oldAcquis.add(delta));
        balance.setJoursRestants(oldRestants.add(delta));
        balance.setDerniereMaj(OffsetDateTime.now(PARIS));

        LeaveBalance saved = balanceRepo.save(balance);

        auditService.log(
                actorId(auth), "ADJUST_BALANCE", "LeaveBalance", balanceId,
                "acquis=" + oldAcquis + " restants=" + oldRestants,
                "acquis=" + saved.getJoursAcquis() + " restants=" + saved.getJoursRestants()
                + " | " + dto.getReason());

        return toDto(saved);
    }

    // ── Balance deduction / restoration (called by AbsenceService) ────────────

    /**
     * Deducts totalJours from the matching balance when an absence is approved.
     * Throws LEAVE_BALANCE_INSUFFICIENT if insufficient days remain.
     */
    public void deduct(Long employeeProfileId, String leaveType, Integer annee, BigDecimal days) {
        LeaveBalance balance = balanceRepo
                .findByEmployeeProfileIdAndAnneeAndLeaveType(employeeProfileId, annee, leaveType)
                .orElseThrow(() -> new AppException(ErrorCode.LEAVE_BALANCE_NOT_FOUND,
                        "Aucun solde " + leaveType + " pour profileId=" + employeeProfileId + " annee=" + annee));

        BigDecimal restants = restants(balance);
        if (restants.compareTo(days) < 0) {
            throw new AppException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                    "Solde insuffisant: " + restants + " jour(s) disponible(s), " + days + " demandé(s)");
        }

        balance.setJoursPris(balance.getJoursPris().add(days));
        balance.setJoursRestants(balance.getJoursAcquis().subtract(balance.getJoursPris()));
        balance.setDerniereMaj(OffsetDateTime.now(PARIS));
        balanceRepo.save(balance);
    }

    /**
     * Restores totalJours to the balance when an absence is refused or archived.
     * Only restores if the absence was previously VALIDE (i.e. days had been deducted).
     */
    public void restore(Long employeeProfileId, String leaveType, Integer annee, BigDecimal days) {
        balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(employeeProfileId, annee, leaveType)
                .ifPresent(balance -> {
                    BigDecimal newPris = balance.getJoursPris().subtract(days).max(BigDecimal.ZERO);
                    balance.setJoursPris(newPris);
                    balance.setJoursRestants(balance.getJoursAcquis().subtract(newPris));
                    balance.setDerniereMaj(OffsetDateTime.now(PARIS));
                    balanceRepo.save(balance);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public LeaveBalance findOrThrow(Long id) {
        return balanceRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.LEAVE_BALANCE_NOT_FOUND, "Solde introuvable: id=" + id));
    }

    private BigDecimal restants(LeaveBalance b) {
        return b.getJoursRestants() != null
                ? b.getJoursRestants()
                : b.getJoursAcquis().subtract(b.getJoursPris());
    }

    LeaveBalanceResponseDto toDto(LeaveBalance b) {
        LeaveBalanceResponseDto dto = new LeaveBalanceResponseDto();
        dto.setId(b.getId());
        dto.setEmployeeProfileId(b.getEmployeeProfileId());
        dto.setAnnee(b.getAnnee());
        dto.setLeaveType(b.getLeaveType());
        dto.setJoursAcquis(b.getJoursAcquis());
        dto.setJoursPris(b.getJoursPris());
        dto.setJoursRestants(restants(b));
        dto.setDerniereMaj(b.getDerniereMaj());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
