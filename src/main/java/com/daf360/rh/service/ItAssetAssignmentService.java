package com.daf360.rh.service;

import com.daf360.rh.domain.ItAsset;
import com.daf360.rh.domain.ItAssetAssignment;
import com.daf360.rh.domain.ItAssetType;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.dto.asset.CreateAssetAssignmentRequest;
import com.daf360.rh.dto.asset.ItAssetAssignmentDto;
import com.daf360.rh.dto.asset.ReturnAssetAssignmentRequest;
import com.daf360.rh.dto.asset.UpdateAssetAssignmentRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The IT equipment ledger — what each employee holds, and what they held before.
 *
 * Three writers, one table:
 *   • onboarding  — {@link #seedFromProvisioning} when IT provisioning is completed
 *   • RH / IT     — the /rh/profiles/:id "Matériel IT" tab (assign, correct, return)
 *   • offboarding — {@link #closeFromOffboardingReturn} when a return is confirmed
 *
 * Rows are never deleted on the normal path: a returned item stays in the history, which
 * is the whole point of the table. {@link #delete} exists for a row created by mistake.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ItAssetAssignmentService {

    /** Same five values as CK_it_asset_status on `it_assets`, so both screens read alike. */
    private static final Set<String> ASSIGN_CONDITIONS =
            Set.of("NEUF", "BON_ETAT", "USAGE", "EN_REPARATION", "DEFECTUEUX");
    /** PERDU only makes sense once the item was supposed to come back. */
    private static final Set<String> RETURN_CONDITIONS =
            Set.of("NEUF", "BON_ETAT", "USAGE", "EN_REPARATION", "DEFECTUEUX", "PERDU");
    /** The three closing statuses. ASSIGNED is not reachable through the return endpoint. */
    private static final Set<String> CLOSING_STATUSES = Set.of(
            ItAssetAssignment.STATUS_RETURNED,
            ItAssetAssignment.STATUS_LOST,
            ItAssetAssignment.STATUS_WRITTEN_OFF);

    private static final String USER_NAME_SQL =
            "SELECT NULLIF(LTRIM(RTRIM(u.fullName)), '') FROM [dbo].[Users] u WHERE u.id = ?";

    /** Whose desk the conflicting serial is on — a 409 that names the holder is actionable. */
    private static final String HOLDER_NAME_SQL =
            "SELECT COALESCE(" +
            "         NULLIF(LTRIM(RTRIM(CONCAT(c.first_name, ' ', c.last_name))), ''), " +
            "         NULLIF(LTRIM(RTRIM(u.fullName)), '')" +
            "       ) " +
            "FROM [dbo].[employee_profiles] ep " +
            "LEFT JOIN [dbo].[candidates] c ON c.id = ep.candidate_id " +
            "LEFT JOIN [dbo].[Users] u      ON u.id = ep.user_id " +
            "WHERE ep.id = ?";

    private final ItAssetAssignmentRepository assignmentRepo;
    private final ItAssetTypeRepository       assetTypeRepo;
    private final EmployeeProfileRepository   profileRepo;
    private final ItProvisioningRepository    provisioningRepo;
    private final ItAssetRepository           itAssetRepo;
    private final AuditService                auditService;
    private final JdbcTemplate                jdbc;

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ItAssetAssignmentDto> getHistory(Long profileId) {
        requireProfile(profileId);
        List<ItAssetAssignment> rows =
                assignmentRepo.findByEmployeeProfileIdOrderByAssignedAtDescIdDesc(profileId);
        Map<Long, ItAssetType> types = loadTypes();
        return rows.stream().map(r -> toDto(r, types)).toList();
    }

    // ── Write — RH / IT tab ───────────────────────────────────────────────────

    public ItAssetAssignmentDto assign(Long profileId, CreateAssetAssignmentRequest req, Long actorId) {
        requireProfile(profileId);

        ItAssetType type = assetTypeRepo.findById(req.getAssetTypeId())
                .orElseThrow(() -> new AppException(ErrorCode.IT_ASSET_TYPE_NOT_FOUND,
                        "Type de matériel introuvable: " + req.getAssetTypeId()));

        String serial    = trimToNull(req.getSerialNumber());
        String condition = defaultIfBlank(req.getConditionOnAssign(), "BON_ETAT");
        requireCondition(condition, ASSIGN_CONDITIONS);
        requireSerialFree(serial);

        ItAssetAssignment row = ItAssetAssignment.builder()
                .employeeProfileId(profileId)
                .assetTypeId(type.getId())
                .serialNumber(serial)
                .brandModel(trimToNull(req.getBrandModel()))
                .assetTag(trimToNull(req.getAssetTag()))
                .assignedAt(req.getAssignedAt())
                .conditionOnAssign(condition)
                .status(ItAssetAssignment.STATUS_ASSIGNED)
                .source(ItAssetAssignment.SOURCE_MANUAL)
                .assignedBy(actorId)
                .notes(trimToNull(req.getNotes()))
                .createdAt(OffsetDateTime.now())
                .build();

        row = assignmentRepo.save(row);
        audit(actorId, "IT_ASSET_ASSIGNED", row.getId(), null,
                "profileId=" + profileId + ";type=" + type.getCode() + ";serial=" + serial);
        return toDto(row, loadTypes());
    }

    public ItAssetAssignmentDto update(Long id, UpdateAssetAssignmentRequest req, Long actorId) {
        ItAssetAssignment row = findOrThrow(id);
        String before = describe(row);

        if (req.getAssetTypeId() != null) {
            ItAssetType type = assetTypeRepo.findById(req.getAssetTypeId())
                    .orElseThrow(() -> new AppException(ErrorCode.IT_ASSET_TYPE_NOT_FOUND,
                            "Type de matériel introuvable: " + req.getAssetTypeId()));
            row.setAssetTypeId(type.getId());
        }
        if (req.getSerialNumber() != null) {
            String serial = trimToNull(req.getSerialNumber());
            // Only re-check when it actually changes, and only while the row is open:
            // a closed row holds no active claim on the serial.
            if (row.getReturnedAt() == null && !java.util.Objects.equals(serial, row.getSerialNumber())) {
                requireSerialFree(serial);
            }
            row.setSerialNumber(serial);
        }
        if (req.getBrandModel() != null) row.setBrandModel(trimToNull(req.getBrandModel()));
        if (req.getAssetTag()   != null) row.setAssetTag(trimToNull(req.getAssetTag()));
        if (req.getNotes()      != null) row.setNotes(trimToNull(req.getNotes()));
        if (req.getConditionOnAssign() != null) {
            String condition = req.getConditionOnAssign().trim();
            requireCondition(condition, ASSIGN_CONDITIONS);
            row.setConditionOnAssign(condition);
        }
        if (req.getAssignedAt() != null) {
            // CK_it_asg_dates would reject this at the database, as a 500 with a constraint
            // name in it. Caught here so the form can say which date is wrong.
            if (row.getReturnedAt() != null && req.getAssignedAt().isAfter(row.getReturnedAt())) {
                throw new AppException(ErrorCode.IT_ASSET_INVALID_STATE,
                        "La date d'affectation ne peut pas être postérieure à la date de retour");
            }
            row.setAssignedAt(req.getAssignedAt());
        }

        row.setUpdatedAt(OffsetDateTime.now());
        row = assignmentRepo.save(row);
        audit(actorId, "IT_ASSET_UPDATED", row.getId(), before, describe(row));
        return toDto(row, loadTypes());
    }

    public ItAssetAssignmentDto returnAsset(Long id, ReturnAssetAssignmentRequest req, Long actorId) {
        ItAssetAssignment row = findOrThrow(id);
        if (row.getReturnedAt() != null) {
            throw new AppException(ErrorCode.IT_ASSET_ALREADY_RETURNED,
                    "Matériel déjà rendu le " + row.getReturnedAt());
        }
        if (req.getReturnedAt().isBefore(row.getAssignedAt())) {
            throw new AppException(ErrorCode.IT_ASSET_INVALID_STATE,
                    "La date de retour ne peut pas précéder la date d'affectation ("
                            + row.getAssignedAt() + ")");
        }

        String status = defaultIfBlank(req.getStatus(), ItAssetAssignment.STATUS_RETURNED);
        if (!CLOSING_STATUSES.contains(status)) {
            throw new AppException(ErrorCode.IT_ASSET_INVALID_STATE,
                    "Statut de clôture invalide: " + status);
        }
        String condition = trimToNull(req.getConditionOnReturn());
        if (condition != null) requireCondition(condition, RETURN_CONDITIONS);

        String before = describe(row);
        row.setReturnedAt(req.getReturnedAt());
        row.setConditionOnReturn(condition);
        row.setStatus(status);
        row.setReturnedBy(actorId);
        if (req.getNotes() != null && !req.getNotes().isBlank()) {
            row.setNotes(appendNote(row.getNotes(), req.getNotes().trim()));
        }
        row.setUpdatedAt(OffsetDateTime.now());
        row = assignmentRepo.save(row);

        audit(actorId, "IT_ASSET_RETURNED", row.getId(), before, describe(row));
        return toDto(row, loadTypes());
    }

    /**
     * For a row created by mistake. A genuine end of possession is a RETURN — deleting it
     * erases the fact that the person ever held the item, which is what this table is for.
     */
    public void delete(Long id, Long actorId) {
        ItAssetAssignment row = findOrThrow(id);
        audit(actorId, "IT_ASSET_ASSIGNMENT_DELETED", row.getId(), describe(row), null);
        assignmentRepo.delete(row);
    }

    // ── Write — onboarding ────────────────────────────────────────────────────

    /**
     * Copies the IT provisioning form into the ledger: every asset marked `provided`
     * becomes an open assignment. A line that is not provided is a planned item, not
     * something the employee holds.
     *
     * Idempotent on (provisioning, asset type) — the same key the V76 backfill uses — so
     * completing a provisioning twice, or re-syncing from the tab, adds nothing.
     *
     * @return the rows created, empty when there is nothing to copy
     */
    public List<ItAssetAssignmentDto> seedFromProvisioning(Long provisioningId, Long actorId) {
        Optional<ItProvisioning> provOpt = provisioningRepo.findById(provisioningId);
        if (provOpt.isEmpty()) return List.of();
        ItProvisioning prov = provOpt.get();

        Optional<com.daf360.rh.domain.EmployeeProfile> profileOpt =
                profileRepo.findByCandidateId(prov.getCandidateId());
        if (profileOpt.isEmpty()) {
            // Expected during onboarding: the provisioning record exists from the moment the
            // offer is accepted, the employee profile only once onboarding creates it. The
            // tab's sync button (and the onboarding hook) picks these up later.
            log.info("seedFromProvisioning: no employee profile yet for candidateId={}",
                    prov.getCandidateId());
            return List.of();
        }
        Long profileId = profileOpt.get().getId();
        LocalDate hireDate = profileOpt.get().getHireDate();
        // Same fallback as the V76 backfill, so a row created by either path carries the
        // same date for the same hire.
        LocalDate assignedAt = hireDate != null ? hireDate
                : (prov.getCreatedAt() != null ? prov.getCreatedAt().toLocalDate() : LocalDate.now());

        Map<Long, ItAssetType> types = loadTypes();
        List<ItAssetAssignmentDto> created = new java.util.ArrayList<>();

        for (ItAsset a : itAssetRepo.findByProvisioningId(prov.getId())) {
            if (!Boolean.TRUE.equals(a.getProvided())) continue;
            Long typeId = a.getAssetType() != null ? a.getAssetType().getId() : null;
            if (typeId == null) continue;
            if (assignmentRepo.existsByItProvisioningIdAndAssetTypeId(prov.getId(), typeId)) continue;

            String serial = trimToNull(a.getSerialNumber());
            // A serial still held by someone else means the two records disagree about
            // where the object is. Skip the line rather than fail the whole completion —
            // provisioning must not be blocked by a data conflict in the ledger.
            if (serial != null
                    && assignmentRepo.findFirstBySerialNumberAndReturnedAtIsNull(serial).isPresent()) {
                log.warn("seedFromProvisioning: serial {} already assigned, skipping asset {}",
                        serial, a.getId());
                continue;
            }

            ItAssetAssignment row = assignmentRepo.save(ItAssetAssignment.builder()
                    .employeeProfileId(profileId)
                    .assetTypeId(typeId)
                    .serialNumber(serial)
                    .brandModel(trimToNull(a.getBrandModel()))
                    .assetTag(trimToNull(a.getAssetTag()))
                    .assignedAt(assignedAt)
                    .conditionOnAssign(ASSIGN_CONDITIONS.contains(a.getStatus())
                            ? a.getStatus() : "BON_ETAT")
                    .status(ItAssetAssignment.STATUS_ASSIGNED)
                    .source(ItAssetAssignment.SOURCE_ONBOARDING)
                    .itProvisioningId(prov.getId())
                    .assignedBy(actorId)
                    .createdAt(OffsetDateTime.now())
                    .build());
            created.add(toDto(row, types));
        }

        if (!created.isEmpty()) {
            audit(actorId, "IT_ASSET_SEEDED_FROM_PROVISIONING", prov.getId(), null,
                    "profileId=" + profileId + ";rows=" + created.size());
            log.info("Seeded {} IT asset assignment(s) for profileId={} from provisioning {}",
                    created.size(), profileId, prov.getId());
        }
        return created;
    }

    /** The tab's "Synchroniser" action: same copy, entered from a profile id. */
    public List<ItAssetAssignmentDto> syncFromProvisioning(Long profileId, Long actorId) {
        Long candidateId = requireProfile(profileId).getCandidateId();
        if (candidateId == null) return List.of();
        return provisioningRepo.findByCandidateId(candidateId)
                .map(prov -> seedFromProvisioning(prov.getId(), actorId))
                .orElseGet(List::of);
    }

    // ── Write — offboarding ───────────────────────────────────────────────────

    /**
     * Closes the ledger row matching a confirmed offboarding return.
     *
     * Matched on the SERIAL, scoped to the leaver's own open rows. Without a serial there
     * is nothing that identifies the object — `asset_description` is a display string —
     * so the row stays open rather than risk closing the wrong laptop. Same reasoning as
     * `reseedItAssets` de-duplicating on the serial rather than the description.
     *
     * Best-effort by design: this is a side effect of confirming a return, and must never
     * be what makes that confirmation fail.
     */
    public void closeFromOffboardingReturn(Long profileId, Long offboardingReturnId,
                                           String serialNumber, LocalDate returnedAt,
                                           String conditionOnReturn, boolean writtenOff,
                                           Long actorId) {
        try {
            String serial = trimToNull(serialNumber);
            if (profileId == null || serial == null || returnedAt == null) return;

            Optional<ItAssetAssignment> match =
                    assignmentRepo.findByEmployeeProfileIdAndReturnedAtIsNull(profileId).stream()
                            .filter(r -> serial.equals(r.getSerialNumber()))
                            .findFirst();
            if (match.isEmpty()) {
                log.info("closeFromOffboardingReturn: no open assignment with serial {} on profile {}",
                        serial, profileId);
                return;
            }

            ItAssetAssignment row = match.get();
            String before = describe(row);
            // The offboarding row can carry a return date earlier than the assignment when the
            // ledger was backfilled with an approximate hire date; CK_it_asg_dates would then
            // reject the update. Clamp instead of losing the closure.
            row.setReturnedAt(returnedAt.isBefore(row.getAssignedAt()) ? row.getAssignedAt() : returnedAt);
            row.setConditionOnReturn(RETURN_CONDITIONS.contains(conditionOnReturn)
                    ? conditionOnReturn : null);
            row.setStatus(writtenOff ? ItAssetAssignment.STATUS_WRITTEN_OFF
                                     : ItAssetAssignment.STATUS_RETURNED);
            row.setOffboardingReturnId(offboardingReturnId);
            row.setReturnedBy(actorId);
            row.setUpdatedAt(OffsetDateTime.now());
            assignmentRepo.save(row);

            audit(actorId, "IT_ASSET_RETURNED_VIA_OFFBOARDING", row.getId(), before, describe(row));
        } catch (Exception ex) {
            log.error("Could not close IT asset assignment for offboarding return {}: {}",
                    offboardingReturnId, ex.getMessage(), ex);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private com.daf360.rh.domain.EmployeeProfile requireProfile(Long profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Profil introuvable: " + profileId));
    }

    private ItAssetAssignment findOrThrow(Long id) {
        return assignmentRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.IT_ASSET_ASSIGNMENT_NOT_FOUND,
                        "Affectation introuvable: " + id));
    }

    /**
     * Enforces UQ_it_asg_active_serial in the service so the conflict comes back as a 409
     * naming the current holder, not as a unique-index violation.
     */
    private void requireSerialFree(String serial) {
        if (serial == null) return;
        assignmentRepo.findFirstBySerialNumberAndReturnedAtIsNull(serial).ifPresent(held -> {
            String holder = resolveName(HOLDER_NAME_SQL, held.getEmployeeProfileId());
            throw new AppException(ErrorCode.IT_ASSET_SERIAL_IN_USE,
                    "Le numéro de série " + serial + " est déjà affecté"
                            + (holder != null ? " à " + holder : "")
                            + " et n'a pas été rendu.");
        });
    }

    private void requireCondition(String condition, Set<String> allowed) {
        if (!allowed.contains(condition)) {
            throw new AppException(ErrorCode.IT_ASSET_INVALID_STATE,
                    "État invalide: " + condition + " (attendu: " + String.join(", ", allowed) + ")");
        }
    }

    private Map<Long, ItAssetType> loadTypes() {
        Map<Long, ItAssetType> byId = new HashMap<>();
        // Every type, active or not: an item handed out under a type since deactivated must
        // still show its label rather than a blank cell.
        for (ItAssetType t : assetTypeRepo.findAll()) byId.put(t.getId(), t);
        return byId;
    }

    private ItAssetAssignmentDto toDto(ItAssetAssignment r, Map<Long, ItAssetType> types) {
        ItAssetType type = types.get(r.getAssetTypeId());
        LocalDate end = r.getReturnedAt() != null ? r.getReturnedAt() : LocalDate.now();
        return ItAssetAssignmentDto.builder()
                .id(r.getId())
                .employeeProfileId(r.getEmployeeProfileId())
                .assetTypeId(r.getAssetTypeId())
                .assetTypeCode(type != null ? type.getCode() : null)
                .assetTypeLabelFr(type != null ? type.getLabelFr() : null)
                .assetTypeLabelEn(type != null ? type.getLabelEn() : null)
                .serialNumber(r.getSerialNumber())
                .brandModel(r.getBrandModel())
                .assetTag(r.getAssetTag())
                .assignedAt(r.getAssignedAt())
                .returnedAt(r.getReturnedAt())
                .conditionOnAssign(r.getConditionOnAssign())
                .conditionOnReturn(r.getConditionOnReturn())
                .status(r.getStatus())
                .source(r.getSource())
                .itProvisioningId(r.getItProvisioningId())
                .offboardingReturnId(r.getOffboardingReturnId())
                .assignedBy(r.getAssignedBy())
                .assignedByName(resolveName(USER_NAME_SQL, r.getAssignedBy()))
                .returnedBy(r.getReturnedBy())
                .returnedByName(resolveName(USER_NAME_SQL, r.getReturnedBy()))
                .notes(r.getNotes())
                .isCurrent(r.getReturnedAt() == null)
                .daysHeld(Math.max(0, ChronoUnit.DAYS.between(r.getAssignedAt(), end)))
                .build();
    }

    private String resolveName(String sql, Long id) {
        if (id == null) return null;
        try {
            return jdbc.queryForObject(sql, String.class, id);
        } catch (Exception ex) {
            log.debug("Could not resolve name for id={}: {}", id, ex.getMessage());
            return null;
        }
    }

    private void audit(Long actorId, String action, Long entityId, String before, String after) {
        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
                action, "ItAssetAssignment", entityId, before, after);
    }

    private String describe(ItAssetAssignment r) {
        return "type=" + r.getAssetTypeId()
                + ";serial=" + r.getSerialNumber()
                + ";assignedAt=" + r.getAssignedAt()
                + ";returnedAt=" + r.getReturnedAt()
                + ";status=" + r.getStatus();
    }

    private static String appendNote(String existing, String added) {
        String merged = (existing == null || existing.isBlank()) ? added : existing + " — " + added;
        return merged.length() > 500 ? merged.substring(0, 500) : merged;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String defaultIfBlank(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }
}
