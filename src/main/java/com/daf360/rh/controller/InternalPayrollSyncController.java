package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.repository.EmployeeProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What daf360-payroll-service needs from RH but cannot know itself:
 *
 *  - one employee's country/contract type, to default a never-configured employee's
 *    Rémunération tab and its "Calculer le net" action, when no
 *    {@code employee_payroll_config} row exists yet for them;
 *  - a country's whole active workforce, for Cohort V2's aggregate budget projections
 *    (grade/discipline/contract-type filters, then a salary modifier run through the payroll
 *    engine — never exposing individual salaries back to the caller).
 *
 * Service-to-service only, authenticated by the {@code X-Internal-Key} shared secret like
 * {@link InternalRegimeController} and {@link InternalSalaryAdvanceController} — fails closed
 * when the key is unset. Path opened in SecurityConfig's existing
 * {@code /api/hr/internal/**} permitAll rule.
 *
 * These are the two endpoints {@code HrEmployeeClient} in payroll-service was always written
 * to call, but which never actually existed here — every call silently 401'd (an unmatched
 * path under Spring Security's default-deny, not a real auth failure). The single-employee
 * lookup surfaced as a visible "no country configured" error; the cohort one was worse —
 * an empty employee list reads as a valid zero-size cohort, so Cohort V2 silently returned a
 * confident-looking €0 projection instead of failing.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalPayrollSyncController {

    private static final String HEADER = "X-Internal-Key";

    /** Mirrors EmployeeProfileRepository.search()'s own "effectif" definition: on the payroll,
     *  present, not yet/no longer counted as staff. */
    private static final Set<LifecycleStatus> ACTIVE_STATUSES =
            EnumSet.of(LifecycleStatus.ACTIVE, LifecycleStatus.ON_LEAVE, LifecycleStatus.ON_MISSION);

    private final EmployeeProfileRepository profileRepo;
    private final AppProperties appProperties;
    private final ContractTypeRefs contractTypeRefs;

    /**
     * GET /api/hr/internal/payroll-sync/employees/{userId}
     * One employee's payroll-relevant profile facts. 204 = no profile for this user (e.g. a
     * pure Users row with no HR dossier) — the caller renders that as "no sensible default
     * available", not an error.
     */
    @GetMapping("/api/hr/internal/payroll-sync/employees/{userId}")
    public ResponseEntity<EmployeeDto> employee(
            @PathVariable Long userId,
            @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        return profileRepo.findByUserId(userId)
                .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * GET /api/hr/internal/payroll-sync/employees?paysId={paysId}
     * The country's active workforce (ACTIVE/ON_LEAVE/ON_MISSION) for Cohort V2's aggregate
     * simulation. An empty list here is a real "no employees match" answer, not a failure —
     * the caller's own demographic filters (grade/discipline/contract type) narrow further.
     */
    @GetMapping("/api/hr/internal/payroll-sync/employees")
    public List<EmployeeDto> employeesByPays(
            @RequestParam Long paysId,
            @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        return profileRepo.findByPaysIdAndLifecycleStatusIn(paysId, ACTIVE_STATUSES).stream()
                .map(this::toDto)
                .toList();
    }

    private EmployeeDto toDto(EmployeeProfile p) {
        return new EmployeeDto(
                p.getUserId(),
                p.getPaysId(),
                contractTypeRefs.codeOf(p.getContractTypeId(), p.getPaysId()),
                p.getGrade() != null ? p.getGrade().getCode() : null,
                p.getDiscipline() != null ? p.getDiscipline().getCode() : null,
                p.getSalaireNetRh());
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        if (provided == null || !java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }

    /** Mirrors daf360-payroll-service's own HrEmployeeClient.HrEmployeeDto shape exactly. */
    public record EmployeeDto(
            Long userId,
            Long paysId,
            String contractType,
            String grade,
            String discipline,
            BigDecimal salaireNetRh
    ) {}
}
