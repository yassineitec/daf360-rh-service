package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.ContractType;
import com.daf360.rh.domain.enums.EmployeeStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class EmployeeResponseDto {
    private Long id;
    private String matricule;
    private String firstName;
    private String lastName;
    private String email;
    private EmployeeStatus status;
    private LocalDate hireDate;
    private ContractType contractType;
    private Long departmentId;
    private Long managerId;
    private String phone;
    private String position;
    private Double annualLeaveBalance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
