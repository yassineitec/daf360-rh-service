package com.daf360.rh.config;

import com.daf360.rh.domain.*;
import com.daf360.rh.domain.enums.*;
import com.daf360.rh.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final DepartmentRepository            departmentRepository;
    private final EmployeeRepository              employeeRepository;
    private final ContractRepository              contractRepository;
    private final LeaveRequestRepository          leaveRequestRepository;
    private final com.daf360.rh.service.RequestTypeCatalogService requestTypeCatalogService;
    private final com.daf360.rh.service.ParameterSetService       parameterSetService;

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Override
    public void run(String... args) {
        if (departmentRepository.count() > 0) {
            log.info("Seed data already present — skipping initialization.");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("system", null, List.of()));
        try {
            seed();
            requestTypeCatalogService.seedDefaults();
            parameterSetService.seedDefaults();
            log.info("Database seeded with initial HR data, request types, and payroll parameters.");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void seed() {

        // ── Departments ──────────────────────────────────────────────────────
        Department hr  = departmentRepository.save(Department.builder().name("Ressources Humaines").code("HR").build());
        Department it  = departmentRepository.save(Department.builder().name("Informatique").code("IT").build());
        Department fin = departmentRepository.save(Department.builder().name("Finance & Comptabilité").code("FIN").build());
        Department sal = departmentRepository.save(Department.builder().name("Commercial").code("SAL").build());
        Department ops = departmentRepository.save(Department.builder().name("Opérations").code("OPS").build());

        // ── Employees ────────────────────────────────────────────────────────
        Employee alice = save(Employee.builder()
                .matricule("EMP-001").firstName("Alice").lastName("Martin")
                .email("alice.martin@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2019, 3, 1)).contractType(ContractType.CDI)
                .departmentId(hr.getId()).position("Responsable RH")
                .phone("+33611111111").annualLeaveBalance(25.0).build());

        Employee bob = save(Employee.builder()
                .matricule("EMP-002").firstName("Bob").lastName("Dupont")
                .email("bob.dupont@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2018, 6, 15)).contractType(ContractType.CDI)
                .departmentId(it.getId()).position("Lead Développeur")
                .phone("+33622222222").annualLeaveBalance(25.0).build());

        Employee caroline = save(Employee.builder()
                .matricule("EMP-003").firstName("Caroline").lastName("Leroy")
                .email("caroline.leroy@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2021, 1, 10)).contractType(ContractType.CDI)
                .departmentId(it.getId()).position("Développeur")
                .phone("+33633333333").annualLeaveBalance(22.0).build());

        Employee david = save(Employee.builder()
                .matricule("EMP-004").firstName("David").lastName("Bernard")
                .email("david.bernard@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2020, 9, 1)).contractType(ContractType.CDI)
                .departmentId(fin.getId()).position("Comptable")
                .phone("+33644444444").annualLeaveBalance(23.0).build());

        Employee emma = save(Employee.builder()
                .matricule("EMP-005").firstName("Emma").lastName("Petit")
                .email("emma.petit@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2017, 11, 20)).contractType(ContractType.CDI)
                .departmentId(sal.getId()).position("Directrice Commerciale")
                .phone("+33655555555").annualLeaveBalance(28.0).build());

        Employee francois = save(Employee.builder()
                .matricule("EMP-006").firstName("François").lastName("Moreau")
                .email("francois.moreau@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2024, 1, 15)).contractType(ContractType.CDD)
                .departmentId(it.getId()).position("Développeur Junior")
                .phone("+33666666666").annualLeaveBalance(10.0).build());

        Employee gabrielle = save(Employee.builder()
                .matricule("EMP-007").firstName("Gabrielle").lastName("Simon")
                .email("gabrielle.simon@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2022, 4, 1)).contractType(ContractType.CDI)
                .departmentId(hr.getId()).position("Assistante RH")
                .phone("+33677777777").annualLeaveBalance(18.0).build());

        Employee hugo = save(Employee.builder()
                .matricule("EMP-008").firstName("Hugo").lastName("Laurent")
                .email("hugo.laurent@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2016, 7, 1)).contractType(ContractType.CDI)
                .departmentId(ops.getId()).position("Directeur Opérations")
                .phone("+33688888888").annualLeaveBalance(30.0).build());

        Employee isabelle = save(Employee.builder()
                .matricule("EMP-009").firstName("Isabelle").lastName("Blanc")
                .email("isabelle.blanc@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2021, 5, 3)).contractType(ContractType.CDI)
                .departmentId(fin.getId()).position("Analyste Financier")
                .phone("+33699999999").annualLeaveBalance(22.0).build());

        Employee julien = save(Employee.builder()
                .matricule("EMP-010").firstName("Julien").lastName("Garnier")
                .email("julien.garnier@daf360.com").status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.of(2024, 3, 1)).contractType(ContractType.CDD)
                .departmentId(sal.getId()).position("Commercial")
                .phone("+33610101010").annualLeaveBalance(8.0).build());

        hr.setManagerId(alice.getId());
        it.setManagerId(bob.getId());
        fin.setManagerId(david.getId());
        sal.setManagerId(emma.getId());
        ops.setManagerId(hugo.getId());
        departmentRepository.saveAll(List.of(hr, it, fin, sal, ops));

        // ── Contracts ────────────────────────────────────────────────────────
        contractRepository.saveAll(List.of(
            Contract.builder().employeeId(alice.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2019, 3, 1)).position("Responsable RH")
                .grossSalary(new BigDecimal("42000.00")).isActive(true).build(),
            Contract.builder().employeeId(bob.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2018, 6, 15)).position("Lead Développeur")
                .grossSalary(new BigDecimal("58000.00")).isActive(true).build(),
            Contract.builder().employeeId(caroline.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2021, 1, 10)).position("Développeur")
                .grossSalary(new BigDecimal("45000.00")).isActive(true).build(),
            Contract.builder().employeeId(david.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2020, 9, 1)).position("Comptable")
                .grossSalary(new BigDecimal("40000.00")).isActive(true).build(),
            Contract.builder().employeeId(emma.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2017, 11, 20)).position("Directrice Commerciale")
                .grossSalary(new BigDecimal("65000.00")).isActive(true).build(),
            Contract.builder().employeeId(francois.getId()).type(ContractType.CDD)
                .startDate(LocalDate.of(2024, 1, 15)).endDate(LocalDate.of(2025, 7, 14))
                .position("Développeur Junior").grossSalary(new BigDecimal("32000.00")).isActive(true).build(),
            Contract.builder().employeeId(gabrielle.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2022, 4, 1)).position("Assistante RH")
                .grossSalary(new BigDecimal("35000.00")).isActive(true).build(),
            Contract.builder().employeeId(hugo.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2016, 7, 1)).position("Directeur Opérations")
                .grossSalary(new BigDecimal("72000.00")).isActive(true).build(),
            Contract.builder().employeeId(isabelle.getId()).type(ContractType.CDI)
                .startDate(LocalDate.of(2021, 5, 3)).position("Analyste Financier")
                .grossSalary(new BigDecimal("43000.00")).isActive(true).build(),
            Contract.builder().employeeId(julien.getId()).type(ContractType.CDD)
                .startDate(LocalDate.of(2024, 3, 1)).endDate(LocalDate.of(2025, 8, 31))
                .position("Commercial").grossSalary(new BigDecimal("30000.00")).isActive(true).build()
        ));

        // ── Leave requests — use DB enum values (CONGE, MALADIE, VALIDE…) ───
        // LocalDate is converted to OffsetDateTime (UTC midnight of local date)
        leaveRequestRepository.saveAll(List.of(
            leave(alice.getId(),    LeaveType.CONGE,        "2025-02-03", "2025-02-07", DemandeEtat.VALIDE,    5, alice.getId(), alice.getId()),
            leave(bob.getId(),      LeaveType.CONGE,        "2025-03-10", "2025-03-14", DemandeEtat.EN_ATTENTE, 5, alice.getId(), null),
            leave(caroline.getId(), LeaveType.MALADIE,      "2025-01-20", "2025-01-22", DemandeEtat.VALIDE,    3, bob.getId(),   alice.getId()),
            leave(david.getId(),    LeaveType.EXCEPTIONNEL, "2025-03-28", "2025-03-28", DemandeEtat.EN_ATTENTE, 1, null,         null),
            leave(emma.getId(),     LeaveType.CONGE,        "2025-04-14", "2025-04-25", DemandeEtat.EN_ATTENTE, 10, null,        null),
            leave(francois.getId(), LeaveType.EXCEPTIONNEL, "2025-02-17", "2025-02-21", DemandeEtat.REFUSE,    5, bob.getId(),   alice.getId()),
            leave(gabrielle.getId(), LeaveType.EXCEPTIONNEL,"2025-03-03", "2025-03-05", DemandeEtat.VALIDE,    3, alice.getId(), alice.getId()),
            leave(hugo.getId(),     LeaveType.CONGE,        "2025-01-06", "2025-01-10", DemandeEtat.VALIDE,    5, hugo.getId(),  alice.getId()),
            leave(isabelle.getId(), LeaveType.EXCEPTIONNEL, "2025-04-04", "2025-04-04", DemandeEtat.EN_ATTENTE, 1, null,         null),
            leave(julien.getId(),   LeaveType.MALADIE,      "2025-02-24", "2025-02-25", DemandeEtat.EN_ATTENTE, 2, david.getId(), null)
        ));

    }

    private Employee save(Employee e) {
        return employeeRepository.save(e);
    }

    private LeaveRequest leave(Long employeeId, LeaveType type,
                               String start, String end,
                               DemandeEtat etat, int days,
                               Long responsableId, Long adjointId) {
        OffsetDateTime s = LocalDate.parse(start).atStartOfDay(PARIS).toOffsetDateTime();
        OffsetDateTime e = LocalDate.parse(end).atStartOfDay(PARIS).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(PARIS);
        return LeaveRequest.builder()
                .employeeId(employeeId)
                .leaveType(type)
                .category(LeaveCategory.FULL_DAY)
                .startDate(s)
                .endDate(e)
                .etatDemande(etat)
                .managerValidatorId(responsableId)
                .hrValidatorId(adjointId)
                .workingDays(BigDecimal.valueOf(days))
                .totalJours(BigDecimal.valueOf(days))
                .dateValidation(etat == DemandeEtat.VALIDE ? now : null)
                .createdAt(now)
                .build();
    }
}
