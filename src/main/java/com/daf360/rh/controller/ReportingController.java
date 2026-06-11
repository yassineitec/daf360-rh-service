package com.daf360.rh.controller;

import com.daf360.rh.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/reporting")
@RequiredArgsConstructor
public class ReportingController {

    private final EmployeeRepository employeeRepository;

    @GetMapping("/absenteisme")
    @PreAuthorize("hasAnyAuthority('GET_GLOBAL_LEAVES', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public Map<String, Object> absenteeismKpis(
            @RequestParam(defaultValue = "0") int year) {
        int effectiveYear = year == 0 ? LocalDate.now().getYear() : year;
        long activeCount = employeeRepository.countActiveEmployees();
        return Map.of(
                "year", effectiveYear,
                "activeEmployees", activeCount,
                "message", "KPI dashboard — integrate with BI module"
        );
    }
}
