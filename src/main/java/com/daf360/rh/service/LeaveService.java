package com.daf360.rh.service;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.domain.enums.LeaveCategory;
import com.daf360.rh.domain.enums.LeaveType;
import com.daf360.rh.dto.LeaveRequestDto;
import com.daf360.rh.dto.LeaveResponseDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.EmployeeRepository;
import com.daf360.rh.repository.LeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRequestRepository leaveRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Transactional(readOnly = true)
    public List<LeaveResponseDto> findByEmployee(Long employeeId) {
        return leaveRepository.findByEmployeeIdOrderByStartDateDesc(employeeId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    /** Returns all requests awaiting validation (EN_ATTENTE). */
    @Transactional(readOnly = true)
    public Page<LeaveResponseDto> findPending(Pageable pageable) {
        return leaveRepository.findByEtatDemande(DemandeEtat.EN_ATTENTE, pageable).map(this::toDto);
    }

    public LeaveResponseDto submit(LeaveRequestDto dto, String actorId) {
        Employee emp = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee", dto.getEmployeeId()));

        int workingDays = countWorkingDays(dto.getStartDate(), dto.getEndDate());

        // BR05: check annual leave balance for CONGE type
        if (dto.getLeaveType() == LeaveType.CONGE) {
            int year = dto.getStartDate().getYear();
            Integer usedDays = leaveRepository.sumApprovedDaysByTypeAndYear(
                    dto.getEmployeeId(), LeaveType.CONGE, year);
            double used = usedDays != null ? usedDays : 0;
            if (emp.getAnnualLeaveBalance() - used < workingDays) {
                throw new BusinessRuleException("BR05", "Solde de congé insuffisant");
            }
        }

        // Dates stored as UTC midnight of local date (Europe/Paris convention)
        OffsetDateTime start = dto.getStartDate().atStartOfDay(PARIS).toOffsetDateTime();
        OffsetDateTime end   = dto.getEndDate().atStartOfDay(PARIS).toOffsetDateTime();

        LeaveRequest lr = LeaveRequest.builder()
                .employeeId(dto.getEmployeeId())
                .leaveType(dto.getLeaveType())
                .category(dto.getCategory() != null ? dto.getCategory() : LeaveCategory.FULL_DAY)
                .startDate(start)
                .endDate(end)
                .etatDemande(DemandeEtat.EN_ATTENTE)
                .comment(dto.getComment())
                .workingDays(BigDecimal.valueOf(workingDays))
                .totalJours(BigDecimal.valueOf(dto.getStartDate().until(dto.getEndDate()).getDays() + 1))
                .createdAt(OffsetDateTime.now(PARIS))
                .build();

        LeaveRequest saved = leaveRepository.save(lr);
        auditService.log(actorId, "SUBMIT_LEAVE", "LeaveRequest", saved.getId(), null, null);
        return toDto(saved);
    }

    /**
     * Step 1 of approval: direct manager validates.
     * Sets responsable_id. State stays EN_ATTENTE until HR also validates.
     */
    public LeaveResponseDto approveByManager(Long id, Long managerId, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        if (lr.getEtatDemande() != DemandeEtat.EN_ATTENTE) {
            throw new BusinessRuleException("La demande n'est pas en attente de validation");
        }
        lr.setManagerValidatorId(managerId);
        lr.setUpdatedAt(OffsetDateTime.now(PARIS));
        return toDto(leaveRepository.save(lr));
    }

    /**
     * Step 2 of approval: HR validates.
     * Requires manager to have already set responsable_id.
     * Sets etatDemande = VALIDE and deducts leave balance for CONGE.
     */
    public LeaveResponseDto approveByHr(Long id, Long hrUserId, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        if (lr.getEtatDemande() != DemandeEtat.EN_ATTENTE) {
            throw new BusinessRuleException("La demande n'est pas en attente de validation");
        }
        if (lr.getManagerValidatorId() == null) {
            throw new BusinessRuleException("La demande doit être validée par le responsable direct d'abord");
        }

        lr.setEtatDemande(DemandeEtat.VALIDE);
        lr.setHrValidatorId(hrUserId);
        lr.setDateValidation(OffsetDateTime.now(PARIS));
        lr.setUpdatedAt(OffsetDateTime.now(PARIS));

        // Deduct from employee's annual leave balance
        if (lr.getLeaveType() == LeaveType.CONGE && lr.getWorkingDays() != null) {
            employeeRepository.findById(lr.getEmployeeId()).ifPresent(emp -> {
                emp.setAnnualLeaveBalance(emp.getAnnualLeaveBalance() - lr.getWorkingDays().doubleValue());
                employeeRepository.save(emp);
            });
        }

        auditService.log(actorId, "APPROVE_LEAVE", "LeaveRequest", id, null, null);
        return toDto(leaveRepository.save(lr));
    }

    public LeaveResponseDto reject(Long id, String reason, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        lr.setEtatDemande(DemandeEtat.REFUSE);
        lr.setRejectionReason(reason);
        lr.setUpdatedAt(OffsetDateTime.now(PARIS));
        auditService.log(actorId, "REJECT_LEAVE", "LeaveRequest", id, null, null);
        return toDto(leaveRepository.save(lr));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private LeaveRequest getOrThrow(Long id) {
        return leaveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest", id));
    }

    private int countWorkingDays(LocalDate start, LocalDate end) {
        int days = 0;
        LocalDate current = start;
        while (!current.isAfter(end)) {
            DayOfWeek dow = current.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) days++;
            current = current.plusDays(1);
        }
        return days;
    }

    public LeaveResponseDto toDto(LeaveRequest lr) {
        LeaveResponseDto dto = new LeaveResponseDto();
        dto.setId(lr.getId());
        dto.setEmployeeId(lr.getEmployeeId());
        dto.setLeaveType(lr.getLeaveType());
        dto.setCategory(lr.getCategory());
        // Convert OffsetDateTime → LocalDate for API response (local Paris date)
        dto.setStartDate(lr.getStartDate() != null
                ? lr.getStartDate().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setEndDate(lr.getEndDate() != null
                ? lr.getEndDate().atZoneSameInstant(PARIS).toLocalDate() : null);
        dto.setEtatDemande(lr.getEtatDemande());
        dto.setWorkingDays(lr.getWorkingDays());
        dto.setTotalJours(lr.getTotalJours());
        dto.setComment(lr.getComment());
        dto.setRejectionReason(lr.getRejectionReason());
        dto.setManagerValidatorId(lr.getManagerValidatorId());
        dto.setHrValidatorId(lr.getHrValidatorId());
        dto.setCreatedAt(lr.getCreatedAt());
        return dto;
    }
}
