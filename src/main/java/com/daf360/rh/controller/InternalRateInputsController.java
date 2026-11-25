package com.daf360.rh.controller;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.service.RegimeResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What facturation's F.04 loaded-cost-rate computation needs from RH: the pays' working
 * staff with their staff type (Technical = billable, Support = non-billable), grade,
 * discipline and weekly regime hours (the ETPT weight).
 *
 * Service-to-service only, authenticated by the {@code X-Internal-Key} shared secret like
 * {@link InternalRegimeController} -- fails closed when the key is unset. Path opened in
 * SecurityConfig's existing {@code /api/hr/internal/**} permitAll rule.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InternalRateInputsController {

    private static final String HEADER = "X-Internal-Key";

    /** Working staff: on duty or on mission. ON_LEAVE is left out (F.04 excludes long leave). */
    private static final Set<LifecycleStatus> WORKING =
            EnumSet.of(LifecycleStatus.ACTIVE, LifecycleStatus.ON_MISSION);

    private final EmployeeProfileRepository profileRepo;
    private final RegimeResolutionService resolutionService;
    private final AppProperties appProperties;

    public record RateInputEmployeeDto(
            Long profileId,
            Long userId,
            String staffType,
            String gradeCode,
            String gradeLabel,
            String disciplineCode,
            String disciplineLabel,
            /** Resolved regime's weekly hours; null when no regime resolves. */
            BigDecimal regimeHoursPerWeek) {}

    /** GET /api/hr/internal/rate-inputs/employees?paysId={paysId} */
    @GetMapping("/api/hr/internal/rate-inputs/employees")
    public List<RateInputEmployeeDto> employees(
            @RequestParam Long paysId,
            @RequestHeader(value = HEADER, required = false) String key) {
        requireInternalKey(key);
        Map<Long, String> staffTypes = new HashMap<>();
        for (Object[] row : profileRepo.findStaffTypesByPaysId(paysId)) {
            String type = row[1] != null ? row[1].toString().trim() : null;
            staffTypes.put(((Number) row[0]).longValue(), type);
        }
        return profileRepo.findByPaysIdAndLifecycleStatusIn(paysId, WORKING).stream()
                .map(p -> toDto(p, staffTypes.get(p.getId())))
                .toList();
    }

    private RateInputEmployeeDto toDto(EmployeeProfile p, String staffType) {
        return new RateInputEmployeeDto(
                p.getId(),
                p.getUserId(),
                staffType,
                p.getGrade() != null ? p.getGrade().getCode() : null,
                p.getGrade() != null ? p.getGrade().getLabelFr() : null,
                p.getDiscipline() != null ? p.getDiscipline().getCode() : null,
                p.getDiscipline() != null ? p.getDiscipline().getLabelFr() : null,
                regimeHours(p.getId()));
    }

    private BigDecimal regimeHours(Long profileId) {
        try {
            ResolvedRegimeDto regime = resolutionService.resolveForEmployee(profileId);
            return regime != null ? regime.getHoursPerWeek() : null;
        } catch (RuntimeException e) {
            log.debug("rate-inputs: no regime for profile {} -- {}", profileId, e.getMessage());
            return null;
        }
    }

    private void requireInternalKey(String provided) {
        String expected = appProperties.getInternalApiKey();
        if (expected == null || expected.isBlank()) {
            log.warn("Internal API called but app.internal-api-key is not configured — denying.");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal API disabled");
        }
        if (provided == null || !constantTimeEquals(expected, provided)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid internal key");
        }
    }

    /** Length-independent comparison so the key cannot be probed byte by byte. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
}
