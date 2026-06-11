package com.daf360.rh.service;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.dto.leave.AbsenceCreateDto;
import com.daf360.rh.dto.leave.AbsenceFilterDto;
import com.daf360.rh.dto.leave.AbsenceResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.LeaveBalanceRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Thin HR wrapper over the existing [absences] table (Timesheet-owned).
 *
 * HR endpoints are under /api/hr/absences — no conflict with Timesheet's own endpoints.
 * This service ADDS balance tracking on top of the existing table;
 * it does NOT change the table structure.
 *
 * Identity bridge: absences.collaborateur_id = Users.id = EmployeeProfile.user_id
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AbsenceService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final LeaveRequestRepository    absenceRepo;
    private final LeaveBalanceRepository    balanceRepo;
    private final EmployeeProfileRepository profileRepo;
    private final LeaveBalanceService       balanceService;
    private final AuditService              auditService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Submits a new absence request for an employee profile.
     *
     * Validations:
     * 1. Profile must exist and have a balance record for the requested leave type
     * 2. Sufficient jours_restants
     * 3. No overlapping EN_ATTENTE or VALIDE absence in the same period
     */
    public AbsenceResponseDto createAbsenceRequest(Long profileId, AbsenceCreateDto dto,
                                                    Authentication auth) {
        var profile = profileRepo.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        Long userId = profile.getUserId();
        int  annee  = dto.getStartDate().getYear();

        // Convert local dates → UTC midnight datetimeoffset (Europe/Paris convention)
        OffsetDateTime start = dto.getStartDate().atStartOfDay(PARIS).toOffsetDateTime();
        OffsetDateTime end   = dto.getEndDate().atStartOfDay(PARIS).toOffsetDateTime();

        // Check balance availability
        var balance = balanceRepo.findByEmployeeProfileIdAndAnneeAndLeaveType(
                profileId, annee, dto.getLeaveType().name())
                .orElseThrow(() -> new AppException(ErrorCode.LEAVE_BALANCE_NOT_FOUND,
                        "Aucun solde " + dto.getLeaveType() + " pour " + annee));

        BigDecimal requested = BigDecimal.valueOf(countWorkingDays(dto.getStartDate(), dto.getEndDate()));
        BigDecimal restants  = balance.getJoursRestants() != null
                             ? balance.getJoursRestants()
                             : balance.getJoursAcquis().subtract(balance.getJoursPris());

        if (restants.compareTo(requested) < 0) {
            throw new AppException(ErrorCode.LEAVE_BALANCE_INSUFFICIENT,
                    "Solde insuffisant: " + restants + " j disponible(s), " + requested + " demandé(s)");
        }

        // Overlap check — no active absence in the same period
        if (!absenceRepo.findOverlapping(userId, start, end).isEmpty()) {
            throw new AppException(ErrorCode.LEAVE_OVERLAP);
        }

        LeaveRequest absence = LeaveRequest.builder()
                .employeeId(userId)
                .leaveType(dto.getLeaveType())
                .category(dto.getCategory() != null ? dto.getCategory() : LeaveCategory.FULL_DAY)
                .startDate(start)
                .endDate(end)
                .etatDemande(DemandeEtat.EN_ATTENTE)
                .comment(dto.getComment())
                .totalJours(BigDecimal.valueOf(dto.getStartDate().until(dto.getEndDate()).getDays() + 1))
                .workingDays(requested)
                .justificatif(dto.getJustificatif() != null && dto.getJustificatif())
                .leaveBalanceId(balance.getId())
                .createdAt(OffsetDateTime.now(PARIS))
                .build();

        LeaveRequest saved = absenceRepo.save(absence);
        auditService.log(actorId(auth), "CREATE_ABSENCE", "Absence", saved.getId(),
                null, dto.getLeaveType().name());
        return toDto(saved);
    }

    // ── Approve ───────────────────────────────────────────────────────────────

    /**
     * Approves the absence:
     * 1. Sets etatDemande = VALIDE, responsable_id, dateValidation
     * 2. Deducts totalJours from the linked leave_balance
     * 3. Writes audit log
     */
    public AbsenceResponseDto approveAbsence(Long absenceId, Long responsableId,
                                              Authentication auth) {
        LeaveRequest absence = findOrThrow(absenceId);

        if (absence.getEtatDemande() != DemandeEtat.EN_ATTENTE) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                    "Seules les demandes EN_ATTENTE peuvent être approuvées");
        }

        absence.setEtatDemande(DemandeEtat.VALIDE);
        absence.setManagerValidatorId(responsableId);
        absence.setDateValidation(OffsetDateTime.now(PARIS));
        absence.setUpdatedAt(OffsetDateTime.now(PARIS));
        LeaveRequest saved = absenceRepo.save(absence);

        // Deduct from balance — profileId must be looked up via userId
        profileRepo.findByUserId(absence.getEmployeeId()).ifPresent(profile ->
                balanceService.deduct(
                        profile.getId(),
                        absence.getLeaveType().name(),
                        absence.getStartDate().atZoneSameInstant(PARIS).getYear(),
                        absence.getWorkingDays() != null ? absence.getWorkingDays() : BigDecimal.ONE));

        auditService.log(actorId(auth), "APPROVE_ABSENCE", "Absence", absenceId, null, "VALIDE");
        return toDto(saved);
    }

    // ── Refuse ────────────────────────────────────────────────────────────────

    /**
     * Refuses the absence:
     * 1. If was VALIDE → restore balance
     * 2. Sets etatDemande = REFUSE, motifRefus
     */
    public AbsenceResponseDto refuseAbsence(Long absenceId, String motif, Authentication auth) {
        LeaveRequest absence = findOrThrow(absenceId);
        boolean wasValide = absence.getEtatDemande() == DemandeEtat.VALIDE;

        absence.setEtatDemande(DemandeEtat.REFUSE);
        absence.setRejectionReason(motif);
        absence.setUpdatedAt(OffsetDateTime.now(PARIS));
        LeaveRequest saved = absenceRepo.save(absence);

        if (wasValide) {
            profileRepo.findByUserId(absence.getEmployeeId()).ifPresent(profile ->
                    balanceService.restore(
                            profile.getId(),
                            absence.getLeaveType().name(),
                            absence.getStartDate().atZoneSameInstant(PARIS).getYear(),
                            absence.getWorkingDays() != null ? absence.getWorkingDays() : BigDecimal.ONE));
        }

        auditService.log(actorId(auth), "REFUSE_ABSENCE", "Absence", absenceId,
                wasValide ? "VALIDE" : "EN_ATTENTE", "REFUSE | " + motif);
        return toDto(saved);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AbsenceResponseDto> listAbsences(AbsenceFilterDto filter, Pageable pageable) {
        if (filter.getProfileId() != null) {
            Long userId = profileRepo.findById(filter.getProfileId())
                    .map(p -> p.getUserId())
                    .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
            return absenceRepo.findByEmployeeIdOrderByStartDateDesc(userId)
                    .stream().map(this::toDto)
                    .collect(java.util.stream.Collectors.collectingAndThen(
                            java.util.stream.Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(
                                    list, pageable, list.size())));
        }
        return absenceRepo.findAll(pageable).map(this::toDto);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LeaveRequest findOrThrow(Long id) {
        return absenceRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.ABSENCE_NOT_FOUND, "Absence introuvable: id=" + id));
    }

    private int countWorkingDays(LocalDate start, LocalDate end) {
        int count = 0;
        LocalDate d = start;
        while (!d.isAfter(end)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) count++;
            d = d.plusDays(1);
        }
        return count;
    }

    AbsenceResponseDto toDto(LeaveRequest a) {
        AbsenceResponseDto dto = new AbsenceResponseDto();
        dto.setId(a.getId());
        dto.setCollaborateurId(a.getEmployeeId());
        dto.setResponsableId(a.getManagerValidatorId());
        dto.setResponsableAdjointId(a.getHrValidatorId());
        dto.setLeaveBalanceId(a.getLeaveBalanceId());
        dto.setLeaveType(a.getLeaveType());
        dto.setCategory(a.getCategory());
        dto.setStartDate(a.getStartDate() != null
                ? a.getStartDate().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setEndDate(a.getEndDate() != null
                ? a.getEndDate().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setEtatDemande(a.getEtatDemande());
        dto.setTotalJours(a.getTotalJours());
        dto.setWorkingDays(a.getWorkingDays());
        dto.setComment(a.getComment());
        dto.setRejectionReason(a.getRejectionReason());
        dto.setJustificatif(a.getJustificatif());
        dto.setDateValidation(a.getDateValidation());
        dto.setCreatedAt(a.getCreatedAt());
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }
}
