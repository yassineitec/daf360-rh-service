package com.daf360.rh.domain;

import com.daf360.rh.audit.AuditableEntity;
import com.daf360.rh.domain.enums.ContractType;
import com.daf360.rh.domain.enums.EmployeeStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "employes")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String matricule;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(unique = true, nullable = false, length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmployeeStatus status;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", length = 20)
    private ContractType contractType;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "archive_date")
    private LocalDate archiveDate;

    @Column(name = "azure_oid", unique = true, length = 100)
    private String azureOid;

    @Column(length = 50)
    private String phone;

    @Column(length = 150)
    private String position;

    @Builder.Default
    @Column(name = "annual_leave_balance")
    private Double annualLeaveBalance = 0.0;
}
