package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegimeResolutionService {

    private final EmployeeProfileRepository profileRepo;
    private final RegimeRoleAssignmentRepository roleAssignRepo;
    private final WorkingTimeRegimeRepository regimeRepo;
    private final JdbcTemplate jdbc;

    /**
     * Resolves which work regime applies to an employee using 3-level priority:
     * 1. Employee personal override (employee_profiles.regime_template_id)
     * 2. Role-based assignment (regime_role_assignments)
     * 3. Pays default (working_time_regimes.is_default=1)
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForEmployee(Long employeeProfileId) {
        EmployeeProfile profile = profileRepo.findById(employeeProfileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee profile not found: " + employeeProfileId));

        // PRIORITY 1: Personal override
        if (profile.getRegimeTemplateId() != null) {
            Optional<WorkingTimeRegime> regime = regimeRepo.findById(profile.getRegimeTemplateId());
            if (regime.isPresent() && Boolean.TRUE.equals(regime.get().getIsActive())) {
                // FIX 5 — pays guard: never return a regime from a different entity
                if (!regime.get().getPaysId().equals(profile.getPaysId())) {
                    log.warn("Employee {} has regime {} from different pays (regime.pays={}, profile.pays={}). Ignoring override.",
                            employeeProfileId, regime.get().getId(), regime.get().getPaysId(), profile.getPaysId());
                } else {
                    return mapToDto(regime.get(), "EMPLOYEE_OVERRIDE",
                            profile.getRegimeStartDate(), profile.getRegimeEndDate());
                }
            } else {
                log.debug("Employee {} has regime_template_id={} but regime is inactive or missing, falling through",
                        employeeProfileId, profile.getRegimeTemplateId());
            }
        }

        // PRIORITY 2: Role-based assignment
        Long roleId = queryUserRoleId(profile.getUserId());
        if (roleId != null) {
            Optional<RegimeRoleAssignment> roleAssign = roleAssignRepo.findActiveForRoleAndPays(
                    roleId, profile.getPaysId(), LocalDate.now());
            if (roleAssign.isPresent()) {
                return mapToDto(roleAssign.get().getRegime(), "ROLE_ASSIGNMENT",
                        roleAssign.get().getEffectiveFrom(), roleAssign.get().getEffectiveTo());
            }
        }

        // PRIORITY 3: Pays default
        Optional<WorkingTimeRegime> defaultRegime = regimeRepo
                .findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(profile.getPaysId());
        if (defaultRegime.isPresent()) {
            return mapToDto(defaultRegime.get(), "DEFAULT", null, null);
        }

        log.warn("No regime configured for employeeProfileId={} (paysId={})",
                employeeProfileId, profile.getPaysId());
        return null;
    }

    /**
     * Resolves which regime applies to a role+pays combination.
     */
    @Transactional(readOnly = true)
    public ResolvedRegimeDto resolveForRole(Long roleId, Long paysId) {
        Optional<RegimeRoleAssignment> assignment = roleAssignRepo
                .findActiveForRoleAndPays(roleId, paysId, LocalDate.now());
        if (assignment.isPresent()) {
            return mapToDto(assignment.get().getRegime(), "ROLE_ASSIGNMENT",
                    assignment.get().getEffectiveFrom(), assignment.get().getEffectiveTo());
        }
        return regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId)
                .map(r -> mapToDto(r, "DEFAULT", null, null))
                .orElse(null);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Long queryUserRoleId(Long userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT role_id FROM [dbo].[Users] WHERE id = ?",
                    Long.class, userId);
        } catch (Exception e) {
            log.debug("Could not fetch role_id for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private ResolvedRegimeDto mapToDto(WorkingTimeRegime r, String level,
                                       LocalDate from, LocalDate to) {
        ResolvedRegimeDto dto = new ResolvedRegimeDto();
        dto.setRegimeId(r.getId());
        dto.setRegimeCode(r.getCode());
        dto.setRegimeLabelFr(r.getLabelFr());
        dto.setRegimeLabelEn(r.getLabelEn());
        dto.setHoursPerWeek(r.getHoursPerWeek());
        dto.setDaysPerWeek(r.getDaysPerWeek());
        dto.setStartTime(r.getStartTime());
        dto.setEndTime(r.getEndTime());
        dto.setIsFlexible(r.getIsFlexible());
        dto.setBreakDurationMin(r.getBreakDurationMin());
        dto.setOvertimeAllowed(r.getOvertimeAllowed());
        dto.setMaxHoursPerDay(r.getMaxHoursPerDay());
        dto.setAssignmentLevel(level);
        dto.setEffectiveFrom(from);
        dto.setEffectiveTo(to);
        return dto;
    }
}
