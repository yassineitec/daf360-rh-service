package com.daf360.rh.service;

import com.daf360.rh.domain.Employee;
import com.daf360.rh.domain.enums.EmployeeStatus;
import com.daf360.rh.dto.EmployeeRequestDto;
import com.daf360.rh.dto.EmployeeResponseDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.EmployeeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<EmployeeResponseDto> findAll(Pageable pageable) {
        return employeeRepository.findByStatusNot(EmployeeStatus.ARCHIVED, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public EmployeeResponseDto findById(Long id) {
        return toDto(getOrThrow(id));
    }

    public EmployeeResponseDto create(EmployeeRequestDto dto, String actorId) {
        if (employeeRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new BusinessRuleException("BR01", "Email already in use: " + dto.getEmail());
        }
        Employee employee = fromDto(dto);
        employee.setStatus(EmployeeStatus.ACTIVE);
        employee.setMatricule(generateMatricule());
        Employee saved = employeeRepository.save(employee);
        auditService.log(actorId, "CREATE", "Employee", saved.getId(), null, toJson(saved));
        return toDto(saved);
    }

    public EmployeeResponseDto update(Long id, EmployeeRequestDto dto, String actorId) {
        Employee existing = getOrThrow(id);
        String before = toJson(existing);

        boolean orgChanged = !Objects.equals(existing.getDepartmentId(), dto.getDepartmentId())
                || !Objects.equals(existing.getManagerId(), dto.getManagerId());

        existing.setFirstName(dto.getFirstName());
        existing.setLastName(dto.getLastName());
        existing.setEmail(dto.getEmail());
        existing.setPhone(dto.getPhone());
        existing.setPosition(dto.getPosition());
        existing.setDepartmentId(dto.getDepartmentId());
        existing.setManagerId(dto.getManagerId());
        existing.setContractType(dto.getContractType());

        Employee saved = employeeRepository.save(existing);
        auditService.log(actorId, "UPDATE", "Employee", saved.getId(), before, toJson(saved));

        if (orgChanged) {
            log.info("Employee {} org changed — notification triggered", id);
        }

        return toDto(saved);
    }

    public void disable(Long id, String actorId) {
        Employee emp = getOrThrow(id);
        String before = toJson(emp);
        emp.setStatus(EmployeeStatus.ARCHIVED);
        emp.setArchiveDate(LocalDate.now());
        employeeRepository.save(emp);
        auditService.log(actorId, "ARCHIVE", "Employee", id, before, toJson(emp));
    }

    private Employee getOrThrow(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    private String generateMatricule() {
        long count = employeeRepository.count();
        return String.format("EMP%05d", count + 1);
    }

    private Employee fromDto(EmployeeRequestDto dto) {
        return Employee.builder()
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .email(dto.getEmail())
                .hireDate(dto.getHireDate())
                .contractType(dto.getContractType())
                .departmentId(dto.getDepartmentId())
                .managerId(dto.getManagerId())
                .phone(dto.getPhone())
                .position(dto.getPosition())
                .azureOid(dto.getAzureOid())
                .annualLeaveBalance(0.0)
                .build();
    }

    public EmployeeResponseDto toDto(Employee e) {
        EmployeeResponseDto dto = new EmployeeResponseDto();
        dto.setId(e.getId());
        dto.setMatricule(e.getMatricule());
        dto.setFirstName(e.getFirstName());
        dto.setLastName(e.getLastName());
        dto.setEmail(e.getEmail());
        dto.setStatus(e.getStatus());
        dto.setHireDate(e.getHireDate());
        dto.setContractType(e.getContractType());
        dto.setDepartmentId(e.getDepartmentId());
        dto.setManagerId(e.getManagerId());
        dto.setPhone(e.getPhone());
        dto.setPosition(e.getPosition());
        dto.setAnnualLeaveBalance(e.getAnnualLeaveBalance());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
