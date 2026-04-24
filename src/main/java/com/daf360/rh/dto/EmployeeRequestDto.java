package com.daf360.rh.dto;

import com.daf360.rh.domain.enums.ContractType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeRequestDto {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    @Email
    private String email;

    @NotNull
    private LocalDate hireDate;

    @NotNull
    private ContractType contractType;

    private Long departmentId;
    private Long managerId;
    private String phone;
    private String position;
    private String azureOid;
}
