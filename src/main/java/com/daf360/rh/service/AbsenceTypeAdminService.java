package com.daf360.rh.service;

import com.daf360.rh.domain.AbsenceType;
import com.daf360.rh.domain.enums.ApproverResolutionStrategy;
import com.daf360.rh.domain.enums.Gender;
import com.daf360.rh.dto.leave.AbsenceTypeDto;
import com.daf360.rh.dto.leave.AbsenceTypeUpsert;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.AbsenceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Administering the leave-type catalogue.
 *
 * This is the screen that makes the model configurable rather than merely data-shaped: the
 * types used to be a Java enum, and every change was a deployment.
 *
 * TWO INVARIANTS THIS SERVICE PROTECTS
 * -----------------------------------------------------------------------------
 *   1. A CODE IS FOREVER. Every filed request stores the code, not the id, and 716 rows
 *      already do. Update ignores the submitted code entirely, so a rename is impossible
 *      through this path rather than merely discouraged.
 *
 *   2. RETIRE, NEVER DELETE. Soft delete only. A hard delete would leave the history filed
 *      under that type unable to resolve a label, a balance rule, or a refund on archive.
 *
 * APPROVER ROLES ARE WRITTEN BY HAND
 * -----------------------------------------------------------------------------
 * `RoleApprovableAbsenceTypes` is an N:M join, and rh's Role entity carries no JPA relations
 * — it holds a plain `parentRoleId`. Following that convention, the join is maintained with
 * JdbcTemplate rather than by introducing the project's first @ManyToMany.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbsenceTypeAdminService {

    /** The only balance columns that exist on Users. */
    private static final List<String> BALANCE_FIELDS = List.of("CONGE", "MALADIE", "TELETRAVAIL");

    private final AbsenceTypeRepository repository;
    private final JdbcTemplate jdbcTemplate;

    // ═══ Read ════════════════════════════════════════════════════════════════

    /** The whole live catalogue, including inactive types — the admin sees everything. */
    @Transactional(readOnly = true)
    public List<AbsenceTypeDto> list() {
        List<AbsenceType> types = repository.findAllLive();
        Map<Long, List<AbsenceTypeDto.ApproverRoleDto>> roles = approverRolesFor(
                types.stream().map(AbsenceType::getId).toList());
        return types.stream().map(t -> toDto(t, roles.getOrDefault(t.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public AbsenceTypeDto get(Long id) {
        AbsenceType t = require(id);
        return toDto(t, approverRolesFor(List.of(id)).getOrDefault(id, List.of()));
    }

    // ═══ Write ═══════════════════════════════════════════════════════════════

    @Transactional
    public AbsenceTypeDto create(AbsenceTypeUpsert dto) {
        String code = dto.getCode().trim().toUpperCase();
        validate(dto);

        // Includes soft-deleted rows on purpose: re-using a retired code would silently
        // adopt its history under new rules.
        repository.findByCode(code).ifPresent(existing -> {
            throw new AppException(ErrorCode.ALREADY_EXISTS, "Le code « " + code + " » existe déjà.");
        });

        AbsenceType t = new AbsenceType();
        t.setCode(code);
        apply(t, dto);
        t.setDeleted(false);
        AbsenceType saved = repository.save(t);

        replaceApproverRoles(saved.getId(), dto.getApproverRoleIds());
        log.info("Absence type {} created ({})", saved.getCode(), saved.getId());
        return get(saved.getId());
    }

    /** The submitted code is ignored — see the class comment. */
    @Transactional
    public AbsenceTypeDto update(Long id, AbsenceTypeUpsert dto) {
        AbsenceType t = require(id);
        validate(dto);
        apply(t, dto);
        repository.save(t);

        replaceApproverRoles(id, dto.getApproverRoleIds());
        log.info("Absence type {} updated", t.getCode());
        return get(id);
    }

    /**
     * Retire a type. Soft delete, and the approver-role rows go with it — a retired type
     * that keeps its restrictions would resurrect them silently if it were ever reactivated.
     */
    @Transactional
    public void retire(Long id) {
        AbsenceType t = require(id);

        long filed = countRequestsUsing(t.getCode());
        t.setDeleted(true);
        t.setDeletedAt(LocalDateTime.now());
        t.setActive(false);
        repository.save(t);
        jdbcTemplate.update("DELETE FROM RoleApprovableAbsenceTypes WHERE absence_type_id = ?", id);

        log.info("Absence type {} retired ({} request(s) already filed under it remain readable)",
                t.getCode(), filed);
    }

    /** Toggle without opening the form — the commonest admin action by far. */
    @Transactional
    public AbsenceTypeDto setActive(Long id, boolean active) {
        AbsenceType t = require(id);
        t.setActive(active);
        repository.save(t);
        return get(id);
    }

    // ═══ Rules ═══════════════════════════════════════════════════════════════

    /**
     * Rejects configurations that would be accepted and then quietly misbehave.
     *
     * The balance pair is the one that matters: `tracksBalance` without a `balanceField`
     * produces a type that claims to debit an allowance and never does, and the employee
     * only discovers it when their balance does not move.
     */
    private void validate(AbsenceTypeUpsert dto) {
        if (dto.isTracksBalance()) {
            String f = dto.getBalanceField() == null ? "" : dto.getBalanceField().trim().toUpperCase();
            if (!BALANCE_FIELDS.contains(f)) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Un type qui suit un solde doit préciser lequel : " + String.join(", ", BALANCE_FIELDS) + ".");
            }
        }
        if (dto.getAllowedGender() != null && !dto.getAllowedGender().isBlank()) {
            try { Gender.valueOf(dto.getAllowedGender()); }
            catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_TRANSITION, "Genre invalide : " + dto.getAllowedGender());
            }
        }
        if (dto.getApproverResolutionStrategy() != null && !dto.getApproverResolutionStrategy().isBlank()) {
            try { ApproverResolutionStrategy.valueOf(dto.getApproverResolutionStrategy()); }
            catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Stratégie invalide : " + dto.getApproverResolutionStrategy());
            }
        }
    }

    private void apply(AbsenceType t, AbsenceTypeUpsert dto) {
        t.setLabelFr(dto.getLabelFr().trim());
        t.setLabelEn(dto.getLabelEn().trim());
        t.setActive(dto.isActive());
        t.setTracksBalance(dto.isTracksBalance());
        // Cleared when the type does not track a balance, so a stale field cannot be read by
        // a later change that flips tracksBalance back on.
        t.setBalanceField(dto.isTracksBalance()
                ? dto.getBalanceField().trim().toUpperCase()
                : null);
        t.setIncludedInHrStats(dto.isIncludedInHrStats());
        t.setRequiresJustification(dto.isRequiresJustification());
        t.setMaxDays(dto.getMaxDays());
        // Null is preserved rather than coerced to 0: null means "use the country default",
        // 0 means "no rule for this type". Collapsing them would silently disable a
        // country-wide policy the administrator never touched.
        t.setAdvanceNoticeDays(dto.getAdvanceNoticeDays());
        t.setLeaveGapDays(dto.getLeaveGapDays());
        t.setDescription(dto.getDescription() == null || dto.getDescription().isBlank()
                ? null : dto.getDescription().trim());
        t.setDisplayOrder(dto.getDisplayOrder());
        t.setManagerCanView(dto.isManagerCanView());
        t.setAllowedGender(dto.getAllowedGender() == null || dto.getAllowedGender().isBlank()
                ? Gender.ALL : Gender.valueOf(dto.getAllowedGender()));
        t.setApproverResolutionStrategy(
                dto.getApproverResolutionStrategy() == null || dto.getApproverResolutionStrategy().isBlank()
                        ? ApproverResolutionStrategy.TOP_OF_HIERARCHY
                        : ApproverResolutionStrategy.valueOf(dto.getApproverResolutionStrategy()));
    }

    // ═══ Approver roles (N:M, by hand) ═══════════════════════════════════════

    /**
     * Replace the whole set rather than diffing it.
     *
     * The join carries no payload beyond the pair, so a delete-then-insert is equivalent to
     * a diff and cannot drift. Null means "not submitted" and leaves the set alone; an empty
     * list means "clear the restriction" — those are different intents and the screen sends
     * them differently.
     */
    private void replaceApproverRoles(Long typeId, List<Long> roleIds) {
        if (roleIds == null) return;
        jdbcTemplate.update("DELETE FROM RoleApprovableAbsenceTypes WHERE absence_type_id = ?", typeId);
        for (Long roleId : roleIds.stream().distinct().toList()) {
            jdbcTemplate.update(
                    "INSERT INTO RoleApprovableAbsenceTypes (role_id, absence_type_id) VALUES (?, ?)",
                    roleId, typeId);
        }
    }

    /** typeId -> its approver roles, for a set of types, in one query. */
    private Map<Long, List<AbsenceTypeDto.ApproverRoleDto>> approverRolesFor(List<Long> typeIds) {
        if (typeIds.isEmpty()) return Map.of();
        String inList = typeIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
        Map<Long, List<AbsenceTypeDto.ApproverRoleDto>> out = new HashMap<>();
        jdbcTemplate.query(
                "SELECT rat.absence_type_id, r.id, r.frenchName "
                        + "FROM RoleApprovableAbsenceTypes rat "
                        + "JOIN Roles r ON r.id = rat.role_id "
                        + "WHERE rat.absence_type_id IN (" + inList + ") "
                        + "  AND (r.deleted = 0 OR r.deleted IS NULL) "
                        + "ORDER BY r.frenchName",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                       .add(new AbsenceTypeDto.ApproverRoleDto(rs.getLong(2), rs.getString(3)));
                });
        return out;
    }

    /** How many requests already reference this code — shown before retiring one. */
    @Transactional(readOnly = true)
    public long countRequestsUsing(String code) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM absences WHERE type = ?", Integer.class, code);
        return n == null ? 0 : n;
    }

    // ═══ Helpers ═════════════════════════════════════════════════════════════

    private AbsenceType require(Long id) {
        return repository.findById(id)
                .filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Type de congé introuvable."));
    }

    private AbsenceTypeDto toDto(AbsenceType t, List<AbsenceTypeDto.ApproverRoleDto> roles) {
        return new AbsenceTypeDto(
                t.getId(),
                t.getCode(),
                t.getLabelFr(),
                t.getLabelEn(),
                Boolean.TRUE.equals(t.getActive()),
                Boolean.TRUE.equals(t.getTracksBalance()),
                t.getBalanceField(),
                Boolean.TRUE.equals(t.getIncludedInHrStats()),
                Boolean.TRUE.equals(t.getRequiresJustification()),
                t.getMaxDays(),
                t.getAdvanceNoticeDays(),
                t.getLeaveGapDays(),
                t.getDescription(),
                t.getDisplayOrder() == null ? 0 : t.getDisplayOrder(),
                t.getAllowedGender() == null ? null : t.getAllowedGender().name(),
                Boolean.TRUE.equals(t.getManagerCanView()),
                t.getApproverResolutionStrategy() == null ? null : t.getApproverResolutionStrategy().name(),
                roles);
    }
}
