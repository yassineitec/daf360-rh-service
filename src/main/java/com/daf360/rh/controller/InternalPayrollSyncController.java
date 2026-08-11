package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.internal.PayrollEmployeeDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Internal endpoint consumed exclusively by the payroll-service for cohort simulations.
 * Not JWT-secured; caller must supply the shared X-Service-Key header whose value
 * is configured via SERVICE_KEY env-var on both services.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/payroll-sync")
@RequiredArgsConstructor
public class InternalPayrollSyncController {

    private final EmployeeProfileRepository profileRepo;
    private final AppProperties             appProperties;

    /**
     * Returns active employees for a given paysId with only the fields the payroll
     * engine needs for aggregate cost simulation.
     *
     * @param paysId    country filter (required)
     * @param serviceKey must match app.service-key configured on both services
     */
    /**
     * Returns a single active employee's payroll-relevant fields by userId.
     * Used by the payroll-service to hydrate individual simulation requests
     * when profileUserId is set (C4 — profile auto-fill).
     */
    @GetMapping("/employees/{userId}")
    public PayrollEmployeeDto getEmployeeForPayroll(
            @PathVariable Long userId,
            @RequestHeader(value = "X-Service-Key", required = false) String serviceKey) {

        String expected = appProperties.getServiceKey();
        if (expected == null || expected.isBlank() || !expected.equals(serviceKey)) {
            log.warn("Rejected /api/internal/payroll-sync/employees/{} — invalid X-Service-Key", userId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid service key");
        }

        EmployeeProfile p = profileRepo.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Employee not found for userId=" + userId));

        return new PayrollEmployeeDto(
                p.getUserId(),
                p.getPaysId(),
                p.getContractType(),
                p.getGrade()      != null ? p.getGrade().getLabelFr()      : null,
                p.getDiscipline() != null ? p.getDiscipline().getLabelFr() : null,
                p.getSalaireNetRh()
        );
    }

    @GetMapping("/employees")
    public List<PayrollEmployeeDto> getEmployeesForPayroll(
            @RequestParam Long paysId,
            @RequestHeader(value = "X-Service-Key", required = false) String serviceKey) {

        String expected = appProperties.getServiceKey();
        if (expected == null || expected.isBlank() || !expected.equals(serviceKey)) {
            log.warn("Rejected /api/internal/payroll-sync/employees — invalid or missing X-Service-Key");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid service key");
        }

        List<EmployeeProfile> profiles =
                profileRepo.findByPaysIdAndLifecycleStatus(paysId, LifecycleStatus.ACTIVE);

        return profiles.stream()
                .filter(p -> p.getSalaireNetRh() != null)
                .map(p -> new PayrollEmployeeDto(
                        p.getUserId(),
                        p.getPaysId(),
                        p.getContractType(),
                        p.getGrade()      != null ? p.getGrade().getLabelFr()      : null,
                        p.getDiscipline() != null ? p.getDiscipline().getLabelFr() : null,
                        p.getSalaireNetRh()
                ))
                .toList();
    }
}
