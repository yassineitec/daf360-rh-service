package com.daf360.rh.service;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.AbsenceType;
import com.daf360.rh.domain.enums.LeaveStatus;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
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

    @Transactional(readOnly = true)
    public List<LeaveResponseDto> findByEmployee(Long employeeId) {
        return leaveRepository.findByEmployeeIdOrderByStartDateDesc(employeeId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<LeaveResponseDto> findPending(Pageable pageable) {
        return leaveRepository.findByStatus(LeaveStatus.PENDING, pageable).map(this::toDto);
    }

    public LeaveResponseDto submit(LeaveRequestDto dto, String actorId) {
        Employee emp = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee", dto.getEmployeeId()));

        // BR05: check leave balance for PAID_LEAVE
        if (dto.getAbsenceType() == AbsenceType.PAID_LEAVE) {
            int year = dto.getStartDate().getYear();
            Integer usedDays = leaveRepository.sumApprovedDaysByTypeAndYear(
                    dto.getEmployeeId(), AbsenceType.PAID_LEAVE, year);
            double used = usedDays != null ? usedDays : 0;
            int requested = countWorkingDays(dto.getStartDate(), dto.getEndDate());
            if (emp.getAnnualLeaveBalance() - used < requested) {
                throw new BusinessRuleException("BR05", "Insufficient leave balance");
            }
        }

        LeaveRequest lr = LeaveRequest.builder()
                .employeeId(dto.getEmployeeId())
                .absenceType(dto.getAbsenceType())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .status(LeaveStatus.PENDING)
                .comment(dto.getComment())
                .workingDays(countWorkingDays(dto.getStartDate(), dto.getEndDate()))
                .build();

        LeaveRequest saved = leaveRepository.save(lr);
        auditService.log(actorId, "SUBMIT_LEAVE", "LeaveRequest", saved.getId(), null, null);
        return toDto(saved);
    }

    public LeaveResponseDto approveByManager(Long id, Long managerId, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        if (lr.getStatus() != LeaveStatus.PENDING) {
            throw new BusinessRuleException("Leave request is not in PENDING state");
        }
        lr.setStatus(LeaveStatus.MANAGER_APPROVED);
        lr.setManagerValidatorId(managerId);
        return toDto(leaveRepository.save(lr));
    }

    public LeaveResponseDto approveByHr(Long id, Long hrUserId, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        if (lr.getStatus() != LeaveStatus.MANAGER_APPROVED) {
            throw new BusinessRuleException("Leave request must be manager-approved first");
        }
        lr.setStatus(LeaveStatus.HR_APPROVED);
        lr.setHrValidatorId(hrUserId);

        // Update employee leave balance
        if (lr.getAbsenceType() == AbsenceType.PAID_LEAVE) {
            employeeRepository.findById(lr.getEmployeeId()).ifPresent(emp -> {
                emp.setAnnualLeaveBalance(emp.getAnnualLeaveBalance() - lr.getWorkingDays());
                employeeRepository.save(emp);
            });
        }

        auditService.log(actorId, "APPROVE_LEAVE", "LeaveRequest", id, null, null);
        return toDto(leaveRepository.save(lr));
    }

    public LeaveResponseDto reject(Long id, String reason, String actorId) {
        LeaveRequest lr = getOrThrow(id);
        lr.setStatus(LeaveStatus.REJECTED);
        lr.setRejectionReason(reason);
        auditService.log(actorId, "REJECT_LEAVE", "LeaveRequest", id, null, null);
        return toDto(leaveRepository.save(lr));
    }

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
        dto.setAbsenceType(lr.getAbsenceType());
        dto.setStartDate(lr.getStartDate());
        dto.setEndDate(lr.getEndDate());
        dto.setStatus(lr.getStatus());
        dto.setWorkingDays(lr.getWorkingDays());
        dto.setComment(lr.getComment());
        dto.setRejectionReason(lr.getRejectionReason());
        dto.setManagerValidatorId(lr.getManagerValidatorId());
        dto.setHrValidatorId(lr.getHrValidatorId());
        dto.setCreatedAt(lr.getCreatedAt());
        return dto;
    }
}
