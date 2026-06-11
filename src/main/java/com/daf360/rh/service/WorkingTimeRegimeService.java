package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.RegimeAssignmentHistory;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.domain.enums.AssignmentLevel;
import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.dto.regime.RegimeAssignmentDto;
import com.daf360.rh.dto.regime.RegimeDetailDto;
import com.daf360.rh.dto.regime.RegimeHistoryItem;
import com.daf360.rh.dto.regime.EmployeeRegimeOverview;
import com.daf360.rh.dto.regime.RegimeOverviewStats;
import com.daf360.rh.dto.regime.RegimeRoleAssignmentResponse;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.WorkingTimeRegimeMapper;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.RoleRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkingTimeRegimeService {

    private final WorkingTimeRegimeRepository    regimeRepository;
    private final EmployeeProfileRepository      profileRepository;
    private final WorkingTimeRegimeMapper        mapper;
    private final AuditService                  auditService;
    private final RegimeRoleAssignmentRepository roleAssignRepo;
    private final RegimeAssignmentHistoryRepository historyRepo;
    private final RoleRepository                 roleRepo;
    private final RegimeResolutionService        resolutionService;
    private final JdbcTemplate                   jdbc;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public WorkingTimeRegimeResponseDto create(WorkingTimeRegimeCreateDto dto, Authentication auth) {
        WorkingTimeRegime regime = mapper.toEntity(dto);
        regime.setCreatedAt(LocalDateTime.now());
        WorkingTimeRegime saved = regimeRepository.save(regime);
        auditService.log(actorId(auth), "CREATE_REGIME", "WorkingTimeRegime", saved.getId(), null, null);
        return mapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<WorkingTimeRegimeResponseDto> listByPays(Long paysId) {
        return regimeRepository.findByPaysIdAndIsActiveTrue(paysId)
                .stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public WorkingTimeRegimeResponseDto getById(Long id) {
        return mapper.toDto(findOrThrow(id));
    }

    public WorkingTimeRegimeResponseDto update(Long id, WorkingTimeRegimeCreateDto dto, Authentication auth) {
        WorkingTimeRegime regime = findOrThrow(id);
        mapper.updateFromDto(dto, regime);
        regime.setUpdatedAt(LocalDateTime.now());
        WorkingTimeRegime saved = regimeRepository.save(regime);
        auditService.log(actorId(auth), "UPDATE_REGIME", "WorkingTimeRegime", id, null, null);
        return mapper.toDto(saved);
    }

    public void deactivate(Long id, Authentication auth) {
        WorkingTimeRegime regime = findOrThrow(id);
        regime.setIsActive(false);
        regime.setUpdatedAt(LocalDateTime.now());
        regimeRepository.save(regime);
        auditService.log(actorId(auth), "DEACTIVATE_REGIME", "WorkingTimeRegime", id, null, null);
    }

    // ── Assign to employee ────────────────────────────────────────────────────

    public void assignToProfile(Long profileId, RegimeAssignmentDto dto, Authentication auth) {
        EmployeeProfile profile = profileRepository.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));

        findOrThrow(dto.getRegimeId());  // validate regime exists

        profile.setRegimeTemplateId(dto.getRegimeId());
        profile.setRegimeStartDate(dto.getStartDate());
        profile.setRegimeEndDate(dto.getEndDate());
        profile.setRegimeReason(dto.getReason());
        profileRepository.save(profile);

        auditService.log(actorId(auth), "ASSIGN_REGIME", "EmployeeProfile", profileId,
                null, "regimeId=" + dto.getRegimeId());
    }

    // ── Delete with validation ────────────────────────────────────────────────

    @Transactional
    public void deleteRegime(Long id, Authentication auth) {
        WorkingTimeRegime regime = findOrThrow(id);
        long empCount = profileRepository.countByRegimeTemplateIdAndDeletedFalse(id);
        if (empCount > 0) throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
            "Ce régime est utilisé directement par " + empCount + " employé(s). Réassignez-les avant de supprimer ce régime.");
        if (roleAssignRepo.existsByRegimeIdAndIsActiveTrue(id))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Ce régime est assigné à un ou plusieurs rôles. Supprimez les assignations de rôles d'abord.");
        if (Boolean.TRUE.equals(regime.getIsDefault()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Impossible de supprimer le régime par défaut. Définissez un autre régime par défaut d'abord.");
        regime.setIsActive(false);
        regime.setUpdatedAt(LocalDateTime.now());
        regimeRepository.save(regime);
        auditService.log(actorId(auth), "DELETE_REGIME", "WorkingTimeRegime", id, null, null);
    }

    // ── Get regime detail with usage counts ──────────────────────────────────

    @Transactional(readOnly = true)
    public RegimeDetailDto getRegimeDetail(Long id) {
        WorkingTimeRegime regime = findOrThrow(id);
        long empCount  = profileRepository.countByRegimeTemplateIdAndDeletedFalse(id);
        long roleCount = roleAssignRepo.countByRegimeIdAndIsActiveTrue(id);
        WorkingTimeRegimeResponseDto base = mapper.toDto(regime);
        RegimeDetailDto dto = new RegimeDetailDto();
        dto.setId(base.getId());
        dto.setPaysId(base.getPaysId());
        dto.setCode(base.getCode());
        dto.setLabelFr(base.getLabelFr());
        dto.setLabelEn(base.getLabelEn());
        dto.setHoursPerWeek(base.getHoursPerWeek());
        dto.setDaysPerWeek(base.getDaysPerWeek());
        dto.setStartTime(base.getStartTime());
        dto.setEndTime(base.getEndTime());
        dto.setIsFlexible(base.getIsFlexible());
        dto.setIsDefault(base.getIsDefault());
        dto.setIsActive(base.getIsActive());
        dto.setEmployeeCount(empCount);
        dto.setRoleCount(roleCount);
        return dto;
    }

    // ── Role assignments ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RegimeRoleAssignmentResponse> listRoleAssignments(Long paysId) {
        return roleAssignRepo.findAllActiveForPays(paysId).stream()
            .map(a -> RegimeRoleAssignmentResponse.builder()
                .id(a.getId())
                .regimeId(a.getRegime().getId())
                .regimeLabelFr(a.getRegime().getLabelFr())
                .roleId(a.getRole().getId())
                .roleName(a.getRole().getFrenchName())
                .paysId(a.getPaysId())
                .effectiveFrom(a.getEffectiveFrom())
                .effectiveTo(a.getEffectiveTo())
                .notes(a.getNotes())
                .assignedBy(a.getAssignedBy())
                .assignedAt(a.getAssignedAt())
                .build())
            .collect(Collectors.toList());
    }

    @Transactional
    public RegimeRoleAssignmentResponse assignRegimeToRole(AssignRegimeToRoleRequest dto, Authentication auth) {
        WorkingTimeRegime regime = findOrThrow(dto.getRegimeId());
        if (!regime.getPaysId().equals(dto.getPaysId()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Ce régime n'appartient pas à cette entité.");
        if (dto.getEffectiveTo() != null && dto.getEffectiveTo().isBefore(dto.getEffectiveFrom()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "La date de fin doit être après la date de début.");
        com.daf360.rh.domain.Role role = roleRepo.findById(dto.getRoleId())
            .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        Optional<RegimeRoleAssignment> previous = roleAssignRepo
            .findActiveForRoleAndPays(dto.getRoleId(), dto.getPaysId(), LocalDate.now());

        if (previous.isPresent()) {
            roleAssignRepo.deactivateForRoleAndPays(dto.getRoleId(), dto.getPaysId(),
                dto.getEffectiveFrom().minusDays(1));
        }

        Long actor = actorLong(auth);
        RegimeRoleAssignment assignment = RegimeRoleAssignment.builder()
            .regime(regime).role(role).paysId(dto.getPaysId())
            .effectiveFrom(dto.getEffectiveFrom()).effectiveTo(dto.getEffectiveTo())
            .isActive(true).assignedBy(actor).assignedAt(OffsetDateTime.now())
            .notes(dto.getNotes()).build();
        roleAssignRepo.save(assignment);

        historyRepo.save(RegimeAssignmentHistory.builder()
            .assignmentLevel(AssignmentLevel.ROLE).targetId(dto.getRoleId())
            .oldRegime(previous.map(RegimeRoleAssignment::getRegime).orElse(null))
            .newRegime(regime).effectiveFrom(dto.getEffectiveFrom()).effectiveTo(dto.getEffectiveTo())
            .changedBy(actor != null ? actor : 0L).changedAt(OffsetDateTime.now()).build());

        auditService.log(actorId(auth), "ASSIGN_REGIME_TO_ROLE", "RegimeRoleAssignment", assignment.getId(), null,
            "roleId=" + dto.getRoleId() + " regimeId=" + dto.getRegimeId());

        return RegimeRoleAssignmentResponse.builder()
            .id(assignment.getId()).regimeId(regime.getId()).regimeLabelFr(regime.getLabelFr())
            .roleId(role.getId()).roleName(role.getFrenchName()).paysId(dto.getPaysId())
            .effectiveFrom(dto.getEffectiveFrom()).effectiveTo(dto.getEffectiveTo())
            .notes(dto.getNotes()).assignedBy(actor).assignedAt(assignment.getAssignedAt()).build();
    }

    @Transactional
    public void removeRoleAssignment(Long assignmentId, Authentication auth) {
        RegimeRoleAssignment assignment = roleAssignRepo.findById(assignmentId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Assignation de régime introuvable: " + assignmentId));
        WorkingTimeRegime oldRegime = assignment.getRegime();
        assignment.setIsActive(false);
        assignment.setEffectiveTo(LocalDate.now());
        roleAssignRepo.save(assignment);
        Long actor = actorLong(auth);
        historyRepo.save(RegimeAssignmentHistory.builder()
            .assignmentLevel(AssignmentLevel.ROLE).targetId(assignment.getRole().getId())
            .oldRegime(oldRegime).newRegime(null)
            .effectiveFrom(assignment.getEffectiveFrom()).effectiveTo(LocalDate.now())
            .changedBy(actor != null ? actor : 0L).changedAt(OffsetDateTime.now()).build());
        auditService.log(actorId(auth), "REMOVE_REGIME_ROLE_ASSIGNMENT", "RegimeRoleAssignment", assignmentId, null, null);
    }

    // ── Employee overrides ────────────────────────────────────────────────────

    @Transactional
    public void assignRegimeToEmployee(Long employeeProfileId, AssignRegimeToEmployeeRequest dto, Authentication auth) {
        com.daf360.rh.domain.EmployeeProfile profile = profileRepository.findById(employeeProfileId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
        WorkingTimeRegime regime = findOrThrow(dto.getRegimeId());
        if (!regime.getPaysId().equals(profile.getPaysId()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Ce régime n'appartient pas à l'entité de l'employé.");
        if (dto.getEffectiveTo() != null && dto.getEffectiveTo().isBefore(dto.getEffectiveFrom()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "La date de fin doit être après la date de début.");

        Long oldRegimeId = profile.getRegimeTemplateId();
        WorkingTimeRegime oldRegime = oldRegimeId != null
            ? regimeRepository.findById(oldRegimeId).orElse(null) : null;

        profile.setRegimeTemplateId(dto.getRegimeId());
        profile.setRegimeStartDate(dto.getEffectiveFrom());
        profile.setRegimeEndDate(dto.getEffectiveTo());
        profile.setRegimeReason(dto.getReason());
        profileRepository.save(profile);

        Long actor = actorLong(auth);
        historyRepo.save(RegimeAssignmentHistory.builder()
            .assignmentLevel(AssignmentLevel.EMPLOYEE).targetId(employeeProfileId)
            .oldRegime(oldRegime).newRegime(regime)
            .effectiveFrom(dto.getEffectiveFrom()).effectiveTo(dto.getEffectiveTo())
            .reason(dto.getReason()).changedBy(actor != null ? actor : 0L).changedAt(OffsetDateTime.now()).build());

        auditService.log(actorId(auth), "ASSIGN_REGIME_TO_EMPLOYEE", "EmployeeProfile", employeeProfileId, null,
            "regimeId=" + dto.getRegimeId());
    }

    @Transactional
    public void removeEmployeeOverride(Long employeeProfileId, Authentication auth) {
        com.daf360.rh.domain.EmployeeProfile profile = profileRepository.findById(employeeProfileId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (profile.getRegimeTemplateId() == null)
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Cet employé n'a pas d'override de régime personnel.");
        Long oldRegimeId = profile.getRegimeTemplateId();
        WorkingTimeRegime oldRegime = regimeRepository.findById(oldRegimeId).orElse(null);
        profile.setRegimeTemplateId(null);
        profile.setRegimeStartDate(null);
        profile.setRegimeEndDate(null);
        profile.setRegimeReason(null);
        profileRepository.save(profile);
        Long actor = actorLong(auth);
        historyRepo.save(RegimeAssignmentHistory.builder()
            .assignmentLevel(AssignmentLevel.EMPLOYEE).targetId(employeeProfileId)
            .oldRegime(oldRegime).newRegime(null)
            .changedBy(actor != null ? actor : 0L).changedAt(OffsetDateTime.now()).build());
        auditService.log(actorId(auth), "REMOVE_EMPLOYEE_REGIME_OVERRIDE", "EmployeeProfile", employeeProfileId, null, null);
    }

    @Transactional(readOnly = true)
    public List<RegimeHistoryItem> getEmployeeRegimeHistory(Long employeeProfileId) {
        if (!profileRepository.existsById(employeeProfileId))
            throw new AppException(ErrorCode.EMPLOYEE_NOT_FOUND);
        return historyRepo.findByAssignmentLevelAndTargetIdOrderByChangedAtDesc(
            AssignmentLevel.EMPLOYEE, employeeProfileId).stream()
            .map(h -> RegimeHistoryItem.builder()
                .id(h.getId())
                .assignmentLevel(h.getAssignmentLevel().name())
                .oldRegimeId(h.getOldRegime() != null ? h.getOldRegime().getId() : null)
                .oldRegimeLabelFr(h.getOldRegime() != null ? h.getOldRegime().getLabelFr() : null)
                .newRegimeId(h.getNewRegime() != null ? h.getNewRegime().getId() : null)
                .newRegimeLabelFr(h.getNewRegime() != null ? h.getNewRegime().getLabelFr() : null)
                .effectiveFrom(h.getEffectiveFrom()).effectiveTo(h.getEffectiveTo())
                .reason(h.getReason()).changedBy(h.getChangedBy()).changedAt(h.getChangedAt()).build())
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RegimeOverviewStats getOverviewStats(Long paysId) {
        long totalRegimes     = regimeRepository.countByPaysIdAndIsActiveTrue(paysId);
        long employeeOverrides = profileRepository.countByPaysIdAndRegimeTemplateIdNotNullAndDeletedFalse(paysId);
        long roleAssignments  = roleAssignRepo.countByPaysIdAndIsActiveTrue(paysId);
        return RegimeOverviewStats.builder()
            .totalRegimes(totalRegimes)
            .employeeOverrideCount(employeeOverrides)
            .roleAssignmentCount(roleAssignments)
            .defaultCount((long) regimeRepository.findByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId).size())
            .unconfiguredCount(0L)
            .build();
    }

    @Transactional(readOnly = true)
    public List<EmployeeRegimeOverview> getOverviewEmployees(Long paysId) {
        List<EmployeeProfile> profiles = profileRepository.findByPaysIdAndDeletedFalse(paysId);
        List<EmployeeRegimeOverview> result = new ArrayList<>();
        for (EmployeeProfile profile : profiles) {
            String fullName = null;
            String roleName = null;
            try {
                fullName = jdbc.queryForObject(
                    "SELECT fullName FROM [dbo].[Users] WHERE id=?", String.class, profile.getUserId());
            } catch (Exception ignored) {}
            try {
                roleName = jdbc.queryForObject(
                    "SELECT r.frenchName FROM [dbo].[Roles] r " +
                    "JOIN [dbo].[Users] u ON u.role_id = r.id WHERE u.id=?",
                    String.class, profile.getUserId());
            } catch (Exception ignored) {}
            if (fullName == null) fullName = "Profil " + profile.getId();
            ResolvedRegimeDto resolved = null;
            try { resolved = resolutionService.resolveForEmployee(profile.getId()); } catch (Exception ignored) {}
            result.add(EmployeeRegimeOverview.builder()
                .employeeProfileId(profile.getId())
                .userId(profile.getUserId())
                .fullName(fullName)
                .roleName(roleName)
                .resolvedRegimeId(resolved != null ? resolved.getRegimeId() : null)
                .resolvedRegimeLabelFr(resolved != null ? resolved.getRegimeLabelFr() : null)
                .assignmentLevel(resolved != null ? resolved.getAssignmentLevel() : null)
                .build());
        }
        return result;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private WorkingTimeRegime findOrThrow(Long id) {
        return regimeRepository.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.REGIME_NOT_FOUND, "Régime introuvable: id=" + id));
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }

    private Long actorLong(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); } catch (NumberFormatException e) { return null; }
    }
}
