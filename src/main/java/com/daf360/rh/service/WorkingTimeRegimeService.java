package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.RegimeAssignmentHistory;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.domain.enums.AssignmentLevel;
import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.dto.regime.EmployeeRegimeAssignmentDto;
import com.daf360.rh.dto.regime.RegimeAssignmentDto;
import com.daf360.rh.dto.regime.RegimeDetailDto;
import com.daf360.rh.dto.regime.RegimeHistoryItem;
import com.daf360.rh.dto.regime.EmployeeRegimeOverview;
import com.daf360.rh.dto.regime.RegimeOverviewStats;
import com.daf360.rh.dto.regime.RegimeRoleAssignmentResponse;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.dto.regime.WorkingTimeRegimeResponseDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.WorkingTimeRegimeMapper;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.RoleRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkingTimeRegimeService {

    /** Who may read another employee's regime (self is always allowed). */
    public static final Set<String> REGIME_READ_AUTHORITIES =
            Set.of("ADMIN_REGIMES", "HR_UPDATE_PROFILE", "HR_CREATE_PROFILE", "HR_ADMIN_ROLES");

    private final WorkingTimeRegimeRepository    regimeRepository;
    private final EmployeeProfileRepository      profileRepository;
    private final WorkingTimeRegimeMapper        mapper;
    private final AuditService                  auditService;
    private final RegimeRoleAssignmentRepository roleAssignRepo;
    private final EmployeeRegimeAssignmentRepository personalRepo;
    private final EmployeeRegimeAssignmentService personalService;
    private final RegimeAssignmentHistoryRepository historyRepo;
    private final RoleRepository                 roleRepo;
    private final RegimeResolutionService        resolutionService;
    private final ScheduleCacheNotifier          cacheNotifier;
    private final TenantService                  tenantService;
    private final JdbcTemplate                   jdbc;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public WorkingTimeRegimeResponseDto create(WorkingTimeRegimeCreateDto dto, Authentication auth) {
        assertInScope(dto.getPaysId());
        // 400 on a bad zone id here, rather than a regime that silently disables the presence
        // automation of everyone assigned to it.
        PaysTimezoneService.requireValidZone(dto.getTimezone());
        WorkingTimeRegime regime = mapper.toEntity(dto);
        regime.setTimezone(blankToNull(dto.getTimezone()));
        normalizeTexts(regime);
        if (regime.getBreakDurationMin() == null) regime.setBreakDurationMin(0);
        if (regime.getOvertimeAllowed() == null) regime.setOvertimeAllowed(false);
        if (regime.getIsFlexible() == null) regime.setIsFlexible(false);
        if (regime.getIsDefault() == null) regime.setIsDefault(false);
        regime.setCreatedAt(LocalDateTime.now());
        regime.setUpdatedBy(actorLong(auth));
        validateDefault(regime);
        if (Boolean.TRUE.equals(regime.getIsDefault())) {
            regimeRepository.clearDefaultForPays(regime.getPaysId(), null);
        }
        WorkingTimeRegime saved = regimeRepository.save(regime);
        auditService.log(actorId(auth), "CREATE_REGIME", "WorkingTimeRegime", saved.getId(), null, null);
        cacheNotifier.everyoneChanged();
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
        PaysTimezoneService.requireValidZone(dto.getTimezone());
        WorkingTimeRegime regime = findOrThrow(id);
        assertInScope(regime.getPaysId());
        Long paysBefore = regime.getPaysId();
        // Cleared while this entity is still clean: once it is dirty, the bulk UPDATE auto-flushes
        // it first and the index sees two defaults. A later validation error rolls this back.
        if (Boolean.TRUE.equals(dto.getIsDefault()) && !Boolean.TRUE.equals(regime.getIsDefault())) {
            regimeRepository.clearDefaultForPays(paysBefore, regime.getId());
        }
        mapper.updateFromDto(dto, regime);
        // A regime never moves to another entity: every assignment pointing to it is
        // validated against the entity it had.
        regime.setPaysId(paysBefore);
        // Applied outside the mapper because it ignores nulls: without this, clearing the
        // seasonal dates in the UI would silently leave the old window in force — and an
        // active seasonal window overrides every other assignment level.
        regime.setSeasonalFrom(dto.getSeasonalFrom());
        regime.setSeasonalTo(dto.getSeasonalTo());
        // Same reason: clearing the timezone must restore inheritance from the entity, not
        // leave a stale override running the regime on another country's clock.
        regime.setTimezone(blankToNull(dto.getTimezone()));
        // The daily cap can be cleared from the form: applied even when null, like the dates.
        regime.setMaxHoursPerDay(dto.getMaxHoursPerDay());
        normalizeTexts(regime);
        regime.setUpdatedAt(LocalDateTime.now());
        regime.setUpdatedBy(actorLong(auth));
        validateDefault(regime);
        if (regime.getSeasonalFrom() != null && isUsedByAssignments(regime.getId(), regime.getPaysId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ce régime est affecté à des employés ou à des rôles : il ne peut pas devenir saisonnier. "
                  + "Retirez d'abord ces affectations, ou créez un régime saisonnier distinct.");
        }
        WorkingTimeRegime saved = regimeRepository.save(regime);
        auditService.log(actorId(auth), "UPDATE_REGIME", "WorkingTimeRegime", id, null, null);
        cacheNotifier.everyoneChanged();
        return mapper.toDto(saved);
    }

    /**
     * Legacy DELETE /api/hr/regimes/{id}. Used to deactivate without any check — a regime
     * still assigned (or the default) silently dropped its employees to the next level.
     * Now the same validated path as /full.
     */
    public void deactivate(Long id, Authentication auth) {
        deleteRegime(id, auth);
    }

    // ── Assign to employee ────────────────────────────────────────────────────

    /**
     * Legacy POST /api/hr/profiles/{id}/regime. Used to write the profile columns with no
     * entity check and no history; now the same path as the override endpoint.
     */
    public void assignToProfile(Long profileId, RegimeAssignmentDto dto, Authentication auth) {
        AssignRegimeToEmployeeRequest req = new AssignRegimeToEmployeeRequest();
        req.setRegimeId(dto.getRegimeId());
        req.setEffectiveFrom(dto.getStartDate());
        req.setEffectiveTo(dto.getEndDate());
        req.setReason(dto.getReason());
        assignRegimeToEmployee(profileId, req, auth);
    }

    // ── Delete with validation ────────────────────────────────────────────────

    @Transactional
    public void deleteRegime(Long id, Authentication auth) {
        WorkingTimeRegime regime = findOrThrow(id);
        assertInScope(regime.getPaysId());
        LocalDate today = resolutionService.todayIn(regime.getPaysId());
        long empCount = personalRepo.countEmployeesUsingFrom(id, today);
        if (empCount > 0) throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
            "Ce régime est utilisé directement par " + empCount + " employé(s) (affectation en cours ou à venir). Réassignez-les avant de supprimer ce régime.");
        if (roleAssignRepo.countUsingRegimeFrom(id, today) > 0)
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Ce régime est assigné à un ou plusieurs rôles (en cours ou à venir). Supprimez les assignations de rôles d'abord.");
        if (Boolean.TRUE.equals(regime.getIsDefault()))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Impossible de supprimer le régime par défaut. Définissez un autre régime par défaut d'abord.");
        regime.setIsActive(false);
        regime.setUpdatedAt(LocalDateTime.now());
        regimeRepository.save(regime);
        auditService.log(actorId(auth), "DELETE_REGIME", "WorkingTimeRegime", id, null, null);
        cacheNotifier.everyoneChanged();
    }

    // ── Get regime detail with usage counts ──────────────────────────────────

    @Transactional(readOnly = true)
    public RegimeDetailDto getRegimeDetail(Long id) {
        WorkingTimeRegime regime = findOrThrow(id);
        LocalDate today = resolutionService.todayIn(regime.getPaysId());
        long empCount  = personalRepo.countEmployeesUsingFrom(id, today);
        long roleCount = roleAssignRepo.countUsingRegimeFrom(id, today);
        WorkingTimeRegimeResponseDto base = mapper.toDto(regime);
        RegimeDetailDto dto = new RegimeDetailDto();
        // Every field of the base DTO, not a hand-kept list: the old list silently left out
        // each field added to the régime since (description, break, overtime, daily cap).
        org.springframework.beans.BeanUtils.copyProperties(base, dto);
        dto.setEmployeeCount(empCount);
        dto.setRoleCount(roleCount);
        return dto;
    }

    // ── Role assignments ──────────────────────────────────────────────────────

    /** In force today or scheduled later (ended and cancelled rows are not listed). */
    @Transactional(readOnly = true)
    public List<RegimeRoleAssignmentResponse> listRoleAssignments(Long paysId) {
        assertInScope(paysId);
        LocalDate today = resolutionService.todayIn(paysId);
        return roleAssignRepo.findCurrentAndUpcomingForPays(paysId, today).stream()
            .map(a -> toResponse(a, today))
            .collect(Collectors.toList());
    }

    /**
     * Same window rules as personal assignments (EmployeeRegimeAssignmentService):
     * open-ended from D → rows started before D end on D-1 and rows starting on/after D are
     * cancelled; bounded → laid on top, nothing touched.
     *
     * The old code set is_active = 0 on the CURRENT row at once, even for a D next month —
     * the role then fell back to the entity default until D.
     */
    @Transactional
    public RegimeRoleAssignmentResponse assignRegimeToRole(AssignRegimeToRoleRequest dto, Authentication auth) {
        assertInScope(dto.getPaysId());
        WorkingTimeRegime regime = findOrThrow(dto.getRegimeId());
        EmployeeRegimeAssignmentService.validateAssignable(regime, dto.getPaysId(),
                dto.getEffectiveFrom(), dto.getEffectiveTo());
        com.daf360.rh.domain.Role role = roleRepo.findById(dto.getRoleId())
            .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        LocalDate from = dto.getEffectiveFrom();
        WorkingTimeRegime previous = roleAssignRepo
            .findActiveForRoleAndPays(dto.getRoleId(), dto.getPaysId(), from)
            .map(RegimeRoleAssignment::getRegime).orElse(null);

        if (dto.getEffectiveTo() == null) {
            for (RegimeRoleAssignment r : roleAssignRepo.findAllNonCancelledForRoleAndPays(dto.getRoleId(), dto.getPaysId())) {
                if (!r.getEffectiveFrom().isBefore(from)) {
                    r.setIsActive(false);                      // never applied: cancelled
                    roleAssignRepo.save(r);
                } else if (r.getEffectiveTo() == null || !r.getEffectiveTo().isBefore(from)) {
                    r.setEffectiveTo(from.minusDays(1));       // keeps applying until D-1
                    roleAssignRepo.save(r);
                }
            }
        }

        Long actor = actorLong(auth);
        RegimeRoleAssignment assignment = RegimeRoleAssignment.builder()
            .regime(regime).role(role).paysId(dto.getPaysId())
            .effectiveFrom(from).effectiveTo(dto.getEffectiveTo())
            .isActive(true).assignedBy(actor).assignedAt(OffsetDateTime.now())
            .notes(dto.getNotes()).build();
        roleAssignRepo.save(assignment);

        roleHistory(dto.getRoleId(), previous, regime, from, dto.getEffectiveTo(), actor);
        auditService.log(actorId(auth), "ASSIGN_REGIME_TO_ROLE", "RegimeRoleAssignment", assignment.getId(), null,
            "roleId=" + dto.getRoleId() + " regimeId=" + dto.getRegimeId());
        cacheNotifier.everyoneChanged();

        return toResponse(assignment, resolutionService.todayIn(dto.getPaysId()));
    }

    /**
     * Started before today → ends yesterday (stays in the history). Starts today or later
     * → cancelled. Already ended → refused.
     */
    @Transactional
    public void removeRoleAssignment(Long assignmentId, Authentication auth) {
        RegimeRoleAssignment assignment = roleAssignRepo.findById(assignmentId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Assignation de régime introuvable: " + assignmentId));
        assertInScope(assignment.getPaysId());
        LocalDate today = resolutionService.todayIn(assignment.getPaysId());
        if (assignment.getEffectiveTo() != null && assignment.getEffectiveTo().isBefore(today))
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "Cette assignation est déjà terminée.");
        WorkingTimeRegime oldRegime = assignment.getRegime();
        if (!assignment.getEffectiveFrom().isBefore(today)) {
            assignment.setIsActive(false);
        } else {
            assignment.setEffectiveTo(today.minusDays(1));
        }
        roleAssignRepo.save(assignment);
        roleHistory(assignment.getRole().getId(), oldRegime, null,
                assignment.getEffectiveFrom(), assignment.getEffectiveTo(), actorLong(auth));
        auditService.log(actorId(auth), "REMOVE_REGIME_ROLE_ASSIGNMENT", "RegimeRoleAssignment", assignmentId, null, null);
        cacheNotifier.everyoneChanged();
    }

    // ── Employee (personal) assignments ───────────────────────────────────────

    @Transactional
    public void assignRegimeToEmployee(Long employeeProfileId, AssignRegimeToEmployeeRequest dto, Authentication auth) {
        EmployeeProfile profile = profileOrThrow(employeeProfileId);
        assertInScope(profile.getPaysId());
        EmployeeRegimeAssignment saved = personalService.assign(profile, dto.getRegimeId(),
                dto.getEffectiveFrom(), dto.getEffectiveTo(), dto.getReason(),
                EmployeeRegimeAssignment.SOURCE_MANUAL, actorLong(auth));
        auditService.log(actorId(auth), "ASSIGN_REGIME_TO_EMPLOYEE", "EmployeeProfile", employeeProfileId, null,
            "regimeId=" + dto.getRegimeId() + " assignmentId=" + saved.getId());
    }

    /** Ends every current and upcoming personal assignment (back to role / default). */
    @Transactional
    public void removeEmployeeOverride(Long employeeProfileId, Authentication auth) {
        EmployeeProfile profile = profileOrThrow(employeeProfileId);
        assertInScope(profile.getPaysId());
        personalService.endAll(profile, actorLong(auth));
        auditService.log(actorId(auth), "REMOVE_EMPLOYEE_REGIME_OVERRIDE", "EmployeeProfile", employeeProfileId, null, null);
    }

    @Transactional
    public void removeEmployeeAssignment(Long employeeProfileId, Long assignmentId, Authentication auth) {
        EmployeeProfile profile = profileOrThrow(employeeProfileId);
        assertInScope(profile.getPaysId());
        personalService.end(profile, assignmentId, actorLong(auth));
        auditService.log(actorId(auth), "END_EMPLOYEE_REGIME_ASSIGNMENT", "EmployeeProfile", employeeProfileId, null,
            "assignmentId=" + assignmentId);
    }

    @Transactional(readOnly = true)
    public List<EmployeeRegimeAssignmentDto> listEmployeeAssignments(Long employeeProfileId) {
        EmployeeProfile profile = profileOrThrow(employeeProfileId);
        assertInScope(profile.getPaysId());
        return personalService.currentAndUpcoming(profile);
    }

    @Transactional(readOnly = true)
    public List<RegimeHistoryItem> getEmployeeRegimeHistory(Long employeeProfileId) {
        EmployeeProfile profile = profileOrThrow(employeeProfileId);
        assertInScope(profile.getPaysId());
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

    // ── Overview ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public RegimeOverviewStats getOverviewStats(Long paysId) {
        assertInScope(paysId);
        LocalDate today = resolutionService.todayIn(paysId);
        List<EmployeeProfile> profiles = profileRepository.findByPaysIdAndDeletedFalse(paysId);
        Map<Long, RegimeResolutionService.ResolvedLevel> levels =
                resolutionService.resolveLevelsForPays(paysId, profiles, loadUsers(paysId).roleByUser());
        long personal = levels.values().stream().filter(l -> l.personalRegimeId() != null).count();
        return RegimeOverviewStats.builder()
            .totalRegimes(regimeRepository.countByPaysIdAndIsActiveTrue(paysId))
            .employeeOverrideCount(personal)
            .roleAssignmentCount(roleAssignRepo.countRolesInForce(paysId, today))
            .defaultCount((long) regimeRepository.findByPaysIdAndIsDefaultTrueAndIsActiveTrue(paysId).size())
            .unconfiguredCount((long) (profiles.size() - levels.size()))
            .build();
    }

    @Transactional(readOnly = true)
    public List<EmployeeRegimeOverview> getOverviewEmployees(Long paysId) {
        assertInScope(paysId);
        List<EmployeeProfile> profiles = profileRepository.findByPaysIdAndDeletedFalse(paysId);
        UserRows users = loadUsers(paysId);
        Map<Long, RegimeResolutionService.ResolvedLevel> levels =
                resolutionService.resolveLevelsForPays(paysId, profiles, users.roleByUser());
        List<EmployeeRegimeOverview> result = new ArrayList<>();
        for (EmployeeProfile profile : profiles) {
            UserRow u = users.byId().get(profile.getUserId());
            RegimeResolutionService.ResolvedLevel l = levels.get(profile.getId());
            // No user name → fullName stays null; the UI labels it "Profil N" / "Profile N".
            result.add(EmployeeRegimeOverview.builder()
                .employeeProfileId(profile.getId())
                .userId(profile.getUserId())
                .fullName(u != null ? u.fullName() : null)
                .roleName(u != null ? u.roleFr() : null)
                .roleNameEn(u != null ? u.roleEn() : null)
                .resolvedRegimeId(l != null ? l.regimeId() : null)
                .resolvedRegimeLabelFr(l != null ? l.regimeLabelFr() : null)
                .assignmentLevel(l != null ? l.level() : null)
                .personalRegimeId(l != null ? l.personalRegimeId() : null)
                .build());
        }
        return result;
    }

    private record UserRow(Long id, String fullName, Long roleId, String roleFr, String roleEn) {}
    private record UserRows(Map<Long, UserRow> byId) {
        Map<Long, Long> roleByUser() {
            Map<Long, Long> m = new HashMap<>();
            byId.forEach((k, v) -> m.put(k, v.roleId()));
            return m;
        }
    }

    /** One query for the names and roles of every profile of the entity (was 2 per employee). */
    private UserRows loadUsers(Long paysId) {
        Map<Long, UserRow> byId = new HashMap<>();
        jdbc.query(
            "SELECT u.id, u.fullName, u.role_id, r.frenchName, r.englishName " +
            "FROM [dbo].[Users] u LEFT JOIN [dbo].[Roles] r ON r.id = u.role_id " +
            "WHERE u.id IN (SELECT p.user_id FROM [dbo].[employee_profiles] p " +
            "               WHERE p.pays_id = ? AND p.deleted = 0)",
            rs -> {
                long roleId = rs.getLong("role_id");
                Long role = rs.wasNull() ? null : roleId;
                long id = rs.getLong("id");
                byId.put(id, new UserRow(id, rs.getString("fullName"), role,
                        rs.getString("frenchName"), rs.getString("englishName")));
            }, paysId);
        return new UserRows(byId);
    }

    // ── Access helpers (controller) ───────────────────────────────────────────

    /**
     * Reading a resolved regime: the employee themself, or someone with an HR/regime
     * authority over the employee's entity. These routes used to be open to any logged-in
     * user for any employee.
     */
    @Transactional(readOnly = true)
    public void assertCanReadUserRegime(Long userId, Authentication auth) {
        Long me = actorLong(auth);
        if (me != null && me.equals(userId)) return;
        if (!hasAny(auth, REGIME_READ_AUTHORITIES))
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez consulter que votre propre régime horaire.");
        profileRepository.findByUserId(userId).ifPresent(p -> assertInScope(p.getPaysId()));
    }

    @Transactional(readOnly = true)
    public void assertCanReadProfileRegime(Long profileId, Authentication auth) {
        EmployeeProfile p = profileOrThrow(profileId);
        Long me = actorLong(auth);
        if (me != null && me.equals(p.getUserId())) return;
        if (!hasAny(auth, REGIME_READ_AUTHORITIES))
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez consulter que votre propre régime horaire.");
        assertInScope(p.getPaysId());
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Refuses an entity outside the caller's scope (V74), like PaysSchedulingService.
     * Unresolved scope stays permissive: an incomplete token loses nothing it had before.
     */
    private void assertInScope(Long paysId) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope == null || scope.unfiltered() || paysId == null) return;
        if (!scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Cette entité n'est pas dans votre périmètre.");
        }
    }

    /**
     * label_en is NOT NULL: an empty English label falls back to the French one. Empty
     * descriptions are stored as NULL, not ''.
     */
    private static void normalizeTexts(WorkingTimeRegime r) {
        if (r.getLabelEn() == null || r.getLabelEn().isBlank()) r.setLabelEn(r.getLabelFr());
        r.setDescriptionFr(blankToNull(r.getDescriptionFr()));
        r.setDescriptionEn(blankToNull(r.getDescriptionEn()));
    }

    /** A seasonal regime applies through its own window to everyone: it is never the default. */
    private static void validateDefault(WorkingTimeRegime r) {
        if (Boolean.TRUE.equals(r.getIsDefault()) && r.getSeasonalFrom() != null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Un régime saisonnier ne peut pas être le régime par défaut de l'entité.");
        }
        if (Boolean.TRUE.equals(r.getIsDefault()) && !Boolean.TRUE.equals(r.getIsActive())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Un régime désactivé ne peut pas être le régime par défaut.");
        }
    }

    private boolean isUsedByAssignments(Long regimeId, Long paysId) {
        LocalDate today = resolutionService.todayIn(paysId);
        return personalRepo.countEmployeesUsingFrom(regimeId, today) > 0
            || roleAssignRepo.countUsingRegimeFrom(regimeId, today) > 0;
    }

    private RegimeRoleAssignmentResponse toResponse(RegimeRoleAssignment a, LocalDate today) {
        return RegimeRoleAssignmentResponse.builder()
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
            .upcoming(a.getEffectiveFrom().isAfter(today))
            .build();
    }

    private void roleHistory(Long roleId, WorkingTimeRegime oldR, WorkingTimeRegime newR,
                             LocalDate from, LocalDate to, Long actor) {
        // changed_by is NOT NULL + FK Users: the old fallback to 0 failed the insert.
        if (actor == null) return;
        historyRepo.save(RegimeAssignmentHistory.builder()
            .assignmentLevel(AssignmentLevel.ROLE).targetId(roleId)
            .oldRegime(oldR).newRegime(newR).effectiveFrom(from).effectiveTo(to)
            .changedBy(actor).changedAt(OffsetDateTime.now()).build());
    }

    private EmployeeProfile profileOrThrow(Long id) {
        return profileRepository.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    /** "" from an empty form control means "inherit", which the column stores as NULL. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static boolean hasAny(Authentication auth, Set<String> authorities) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authorities.contains(a.getAuthority()));
    }

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
