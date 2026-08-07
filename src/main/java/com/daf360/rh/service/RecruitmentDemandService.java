package com.daf360.rh.service;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.dto.recruitment.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.lists.ConfigurableListTypeRepository;
import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.repository.RecruitmentDemandRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RecruitmentDemandService {

    private final RecruitmentDemandRepository demandRepo;
    private final ConfigurableListValueRepository listValueRepo;
    private final ConfigurableListTypeRepository  listTypeRepo;
    private final AuditService  auditService;
    private final MailService   mailService;
    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;
    private final com.daf360.rh.security.TenantService tenantService;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    // ── SQL constants ─────────────────────────────────────────────────────────

    private static final String USERS_WITH_PERMISSION_SQL = """
        SELECT DISTINCT u.id
          FROM [dbo].[Users] u
          JOIN [dbo].[Roles] r             ON r.id    = u.role_id
          JOIN [dbo].[RolePermissions] rp  ON rp.role_id = r.id
         WHERE rp.permission = ?
           AND u.pays_id     = ?
           AND (u.isActive = 1 OR u.isActive IS NULL)
        """;

    private static final String USERS_EMAILS_WITH_PERMISSION_SQL = """
        SELECT DISTINCT u.email
          FROM [dbo].[Users] u
          JOIN [dbo].[Roles] r             ON r.id    = u.role_id
          JOIN [dbo].[RolePermissions] rp  ON rp.role_id = r.id
         WHERE rp.permission = ?
           AND u.pays_id     = ?
           AND (u.isActive = 1 OR u.isActive IS NULL)
           AND u.email IS NOT NULL AND u.email <> ''
        """;

    private static final String INSERT_NOTIFICATION_SQL = """
        INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at)
        VALUES (?, 'HR', ?, ?, 0, SYSDATETIMEOFFSET())
        """;

    // ── Public API ─────────────────────────────────────────────────────────────

    public RecruitmentDemandResponse create(CreateRecruitmentDemandRequest request, Long actorUserId) {
        // Callers may send an id OR a value_code. The RH module's own form holds ids (it loads
        // the lists to build its selects); the self-service form drives sliders over a fixed
        // scale and holds codes, so it never has to fetch a list to fill a required field.
        Long urgencyId = resolveListValue(
                request.getUrgencyLevelId(), request.getUrgencyLevelCode(), "URGENCY_LEVEL");
        if (urgencyId == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le niveau d'urgence est obligatoire (urgencyLevelId ou urgencyLevelCode).");
        }
        Long experienceId = resolveListValue(
                request.getExperienceLevelId(), request.getExperienceLevelCode(), "EXPERIENCE_LEVEL");

        validateListValue(urgencyId, "URGENCY_LEVEL");
        if (request.getCspCategoryId() != null) validateListValue(request.getCspCategoryId(), "CSP_CATEGORY");
        if (experienceId != null) validateListValue(experienceId, "EXPERIENCE_LEVEL");
        if (request.getEducationLevelId()  != null) validateListValue(request.getEducationLevelId(),  "EDUCATION_LEVEL");

        RecruitmentDemand demand = RecruitmentDemand.builder()
                .createdByUserId(actorUserId)
                .paysId(request.getPaysId())
                .jobTitle(request.getJobTitle())
                .jobExactTitle(request.getJobExactTitle())
                .department(request.getDepartment())
                .requiredProfile(request.getRequiredProfile())
                .scopeOfWork(request.getScopeOfWork())
                .needDescription(request.getNeedDescription())
                .urgencyLevelId(urgencyId)
                .recruitmentReason(request.getRecruitmentReason())
                .cspCategoryId(request.getCspCategoryId())
                .experienceLevelId(experienceId)
                // Position dimensions (V72). Stored as given — they are FK-constrained, so a
                // bad id is rejected by the database rather than needing a check here.
                .gradeId(request.getGradeId())
                .disciplineId(request.getDisciplineId())
                .departmentId(request.getDepartmentId())
                .educationLevelId(request.getEducationLevelId())
                .technicalSkillsJson(toJson(request.getTechnicalSkills()))
                .softSkillsJson(toJson(request.getSoftSkills()))
                .targetStartDate(request.getTargetStartDate())
                .headcount(request.getHeadcount())
                .budgetRange(request.getBudgetRange())
                .additionalNotes(request.getAdditionalNotes())
                .statut(RecruitmentDemandStatus.EN_ATTENTE)
                .submittedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();

        demand = demandRepo.save(demand);

        auditService.log(actorUserId.toString(), "CREATE", "RECRUITMENT_DEMAND", demand.getId(),
                null, "jobTitle=" + demand.getJobTitle());

        notifyUsersWithPermission(
                PermissionCatalog.RH_APPROVE_RECRUITMENT_DEMAND,
                demand.getPaysId(),
                "Nouvelle demande de recrutement",
                "Une demande de recrutement pour le poste \"" + demand.getJobTitle() + "\" est en attente d'approbation."
        );

        return toResponse(demand);
    }

    public RecruitmentDemandResponse review(Long id, ReviewRecruitmentDemandRequest request, Long actorUserId) {
        RecruitmentDemand demand = findOrThrow(id);

        if (demand.getStatut() != RecruitmentDemandStatus.EN_ATTENTE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_ALREADY_REVIEWED);
        }

        String before = "statut=" + demand.getStatut();
        RecruitmentDemandStatus newStatut = request.getApproved()
                ? RecruitmentDemandStatus.APPROUVEE
                : RecruitmentDemandStatus.REJETEE;

        demand.setStatut(newStatut);
        demand.setReviewedByUserId(actorUserId);
        demand.setReviewedAt(OffsetDateTime.now());
        demand.setReviewComment(request.getComment());
        demand.setUpdatedAt(OffsetDateTime.now());
        demand = demandRepo.save(demand);

        auditService.log(actorUserId.toString(), "REVIEW", "RECRUITMENT_DEMAND", demand.getId(),
                before, "statut=" + newStatut);

        if (newStatut == RecruitmentDemandStatus.APPROUVEE) {
            onApproved(demand);
        }

        return toResponse(demand);
    }

    public RecruitmentDemandResponse cancel(Long id, Long actorUserId) {
        RecruitmentDemand demand = findOrThrow(id);

        if (demand.getStatut() != RecruitmentDemandStatus.EN_ATTENTE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_INVALID_TRANSITION,
                    "Seules les demandes EN_ATTENTE peuvent être annulées");
        }
        if (!demand.getCreatedByUserId().equals(actorUserId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez annuler que vos propres demandes");
        }

        demand.setStatut(RecruitmentDemandStatus.ANNULEE);
        demand.setUpdatedAt(OffsetDateTime.now());
        demand = demandRepo.save(demand);

        auditService.log(actorUserId.toString(), "CANCEL", "RECRUITMENT_DEMAND", demand.getId(),
                "statut=EN_ATTENTE", "statut=ANNULEE");

        return toResponse(demand);
    }

    @Transactional(readOnly = true)
    public Page<RecruitmentDemandSummary> listByPays(Long paysId, RecruitmentDemandStatus statut, Pageable pageable) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;
        Page<RecruitmentDemand> page = (statut != null)
                ? demandRepo.findByPaysIdAndStatutOrderBySubmittedAtDesc(resolvedPaysId, statut, pageable)
                : demandRepo.findByPaysIdOrderBySubmittedAtDesc(resolvedPaysId, pageable);
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Page<RecruitmentDemandSummary> listMine(Long userId, RecruitmentDemandStatus statut, Pageable pageable) {
        Page<RecruitmentDemand> page = (statut != null)
                ? demandRepo.findByCreatedByUserIdAndStatutOrderBySubmittedAtDesc(userId, statut, pageable)
                : demandRepo.findByCreatedByUserIdOrderBySubmittedAtDesc(userId, pageable);
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public RecruitmentDemandResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ApprovedDemandOption> getApprovedOptions(Long paysId) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;
        return demandRepo.findByPaysIdAndStatutOrderByJobTitleAsc(resolvedPaysId, RecruitmentDemandStatus.APPROUVEE)
                .stream()
                .map(d -> new ApprovedDemandOption(d.getId(), d.getJobTitle(), d.getDepartment()))
                .toList();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private RecruitmentDemand findOrThrow(Long id) {
        return demandRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND));
    }

    /**
     * The id to store, from either an explicit id or a `value_code`.
     *
     * The id wins when both are present — a caller that resolved the list itself is more
     * specific than one naming a step on a fixed scale.
     *
     * Matched in Java over the type's values rather than through a new repository finder:
     * these lists are 4–5 rows, and the ordered fetch already exists.
     *
     * An unknown code THROWS rather than falling back to null. A silent null would store a
     * demand whose urgency nobody chose — and for experience it would quietly drop the
     * manager's answer. If a code does not resolve, the scale and the data have diverged
     * (most likely V71 has not been applied) and that must be visible.
     */
    private Long resolveListValue(Long valueId, String valueCode, String listTypeCode) {
        if (valueId != null) return valueId;
        if (valueCode == null || valueCode.isBlank()) return null;

        Long typeId = ensureListType(listTypeCode);

        var existing = listValueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(typeId).stream()
                .filter(v -> valueCode.equalsIgnoreCase(v.getValueCode()))
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .findFirst();
        if (existing.isPresent()) return existing.get().getId();

        // Not there → provision the whole scale, then look again. See ensureScale.
        ensureScale(listTypeCode, typeId);
        return listValueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(typeId).stream()
                .filter(v -> valueCode.equalsIgnoreCase(v.getValueCode()))
                .filter(v -> Boolean.TRUE.equals(v.getIsActive()))
                .map(com.daf360.rh.lists.ConfigurableListValue::getId)
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Valeur '" + valueCode + "' inconnue dans la liste " + listTypeCode
                      + " et absente de l'échelle de référence."));
    }

    // ── Self-provisioning reference scales ───────────────────────────────────
    //
    // urgency_level_id is a NOT NULL FK, so the hiring form cannot submit without a row to
    // point at — and that made a UI with a FIXED, hardcoded scale fail purely because a seed
    // had not been applied ("Type de liste inconnu: URGENCY_LEVEL", from V31; the same class of
    // failure gave 404s for V32's CSP_CATEGORY and EDUCATION_LEVEL).
    //
    // These two scales are not configuration in any meaningful sense: the form hardcodes their
    // steps, so the rows exist only to satisfy the foreign key. Creating them on demand is
    // therefore safe and removes a whole category of "works on my database" failure.
    //
    // NOT done for CSP_CATEGORY / EDUCATION_LEVEL: those ARE editable configuration (an admin
    // may legitimately reword or reorder them), both are optional on the demand, and inventing
    // rows for them would overwrite a deliberate choice to leave them empty.

    /** The canonical steps, mirroring rh V31 + V71. Order here is the scale's order. */
    private static final Map<String, List<String[]>> REFERENCE_SCALES = Map.of(
            "URGENCY_LEVEL", List.of(
                    new String[] {"FAIBLE",      "Faible",      "Low"},
                    new String[] {"NORMAL",      "Normal",      "Normal"},
                    new String[] {"URGENT",      "Urgent",      "Urgent"},
                    new String[] {"TRES_URGENT", "Très urgent", "Very urgent"},
                    new String[] {"CRITIQUE",    "Critique",    "Critical"}),
            "EXPERIENCE_LEVEL", List.of(
                    new String[] {"DEBUTANT", "Fraîchement diplômé", "Fresh graduate"},
                    new String[] {"JUNIOR",   "Junior (0-2 ans)",    "Junior (0-2 yrs)"},
                    new String[] {"CONFIRME", "Confirmé (3-5 ans)",  "Mid-level (3-5 yrs)"},
                    new String[] {"SENIOR",   "Sénior (6-10 ans)",   "Senior (6-10 yrs)"},
                    new String[] {"EXPERT",   "Expert (+10 ans)",    "Expert (10+ yrs)"}));

    /** The list type's id, creating the type itself if the seed never ran. */
    private Long ensureListType(String listTypeCode) {
        var found = listTypeRepo.findByCode(listTypeCode);
        if (found.isPresent()) return found.get().getId();

        if (!REFERENCE_SCALES.containsKey(listTypeCode)) {
            // An editable list — creating it would be inventing configuration.
            throw new AppException(ErrorCode.NOT_FOUND, "Type de liste inconnu: " + listTypeCode);
        }
        log.warn("List type '{}' was missing and has been created automatically — the rh seed "
               + "for it was never applied on this database.", listTypeCode);
        var created = listTypeRepo.save(com.daf360.rh.lists.ConfigurableListType.builder()
                .code(listTypeCode)
                .labelFr("URGENCY_LEVEL".equals(listTypeCode) ? "Niveau d'urgence" : "Niveau d'expérience")
                .labelEn("URGENCY_LEVEL".equals(listTypeCode) ? "Urgency level" : "Experience level")
                .isPerPays(false)   // the scale must read the same for every entity
                .isSystem(false)
                .createdAt(java.time.LocalDateTime.now())
                .build());
        return created.getId();
    }

    /**
     * Adds any missing step of a reference scale. Existing rows are left untouched — an admin
     * may have reworded a label, and this is only here to guarantee the codes resolve.
     */
    private void ensureScale(String listTypeCode, Long typeId) {
        List<String[]> steps = REFERENCE_SCALES.get(listTypeCode);
        if (steps == null) return;

        var present = listValueRepo.findByListTypeIdOrderBySortOrderAscLabelFrAsc(typeId).stream()
                .map(com.daf360.rh.lists.ConfigurableListValue::getValueCode)
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());

        for (int i = 0; i < steps.size(); i++) {
            String[] s = steps.get(i);
            if (present.contains(s[0].toUpperCase())) continue;
            log.warn("Creating missing '{}' value '{}' — apply the rh seeds to avoid this.",
                    listTypeCode, s[0]);
            listValueRepo.save(com.daf360.rh.lists.ConfigurableListValue.builder()
                    .listTypeId(typeId)
                    .valueCode(s[0])
                    .labelFr(s[1])
                    .labelEn(s[2])
                    .sortOrder(i + 1)
                    .isActive(true)
                    .isSystem(false)
                    .createdAt(OffsetDateTime.now())
                    .build());
        }
    }

    private void validateListValue(Long valueId, String listTypeCode) {
        var value = listValueRepo.findById(valueId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Valeur de liste introuvable (id=" + valueId + ")"));
        var listType = listTypeRepo.findByCode(listTypeCode)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                        "Type de liste inconnu: " + listTypeCode));
        if (!value.getListTypeId().equals(listType.getId())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "La valeur sélectionnée n'appartient pas à la liste " + listTypeCode);
        }
    }

    private void onApproved(RecruitmentDemand demand) {
        String title   = "Demande de recrutement approuvée";
        String message = "La demande de recrutement pour le poste \""
                + demand.getJobTitle() + "\" a été approuvée. Vous pouvez maintenant lancer le processus de recrutement.";

        notifyUsersWithPermission(PermissionCatalog.RH_VIEW_RECRUITMENT_DEMAND,
                demand.getPaysId(), title, message);

        sendEmailToRecruitmentTeam(demand, title, message);
    }

    private void notifyUsersWithPermission(String permission, Long paysId, String title, String message) {
        try {
            List<Long> userIds = jdbc.queryForList(USERS_WITH_PERMISSION_SQL, Long.class, permission, paysId);
            for (Long uid : userIds) {
                jdbc.update(INSERT_NOTIFICATION_SQL, uid, title, message);
            }
        } catch (Exception ex) {
            log.error("Failed to send in-app notifications for permission={} pays={}: {}", permission, paysId, ex.getMessage());
        }
    }

    private void sendEmailToRecruitmentTeam(RecruitmentDemand demand, String subject, String body) {
        try {
            List<String> emails = jdbc.queryForList(USERS_EMAILS_WITH_PERMISSION_SQL,
                    String.class,
                    PermissionCatalog.RH_VIEW_RECRUITMENT_DEMAND,
                    demand.getPaysId());
            if (!emails.isEmpty()) {
                String htmlBody = "<p>" + body + "</p>"
                        + "<p><strong>Poste :</strong> " + demand.getJobTitle() + "</p>"
                        + (demand.getDepartment() != null ? "<p><strong>Département :</strong> " + demand.getDepartment() + "</p>" : "")
                        + "<p><strong>Effectif requis :</strong> " + demand.getHeadcount() + "</p>"
                        + (demand.getTargetStartDate() != null ? "<p><strong>Date cible :</strong> " + demand.getTargetStartDate() + "</p>" : "");
                mailService.sendRoutedEmail(emails, List.of(), List.of(), subject, htmlBody);
            }
        } catch (Exception ex) {
            log.error("Failed to send approval email for demandId={}: {}", demand.getId(), ex.getMessage());
        }
    }

    private RecruitmentDemandResponse toResponse(RecruitmentDemand d) {
        RecruitmentDemandResponse r = new RecruitmentDemandResponse();
        r.setId(d.getId());
        r.setCreatedByUserId(d.getCreatedByUserId());
        r.setPaysId(d.getPaysId());
        r.setJobTitle(d.getJobTitle());
        r.setJobExactTitle(d.getJobExactTitle());
        r.setDepartment(d.getDepartment());
        // Position dimensions (V72) — ids for the candidate form's prefill, labels so a reader
        // does not need a second call just to render the vacancy.
        r.setDepartmentId(d.getDepartmentId());
        r.setGradeId(d.getGradeId());
        r.setGradeLabel(resolveDimensionLabel("grades", d.getGradeId()));
        r.setDisciplineId(d.getDisciplineId());
        r.setDisciplineLabel(resolveDimensionLabel("disciplines", d.getDisciplineId()));
        r.setRequiredProfile(d.getRequiredProfile());
        r.setScopeOfWork(d.getScopeOfWork());
        r.setNeedDescription(d.getNeedDescription());
        r.setRecruitmentReason(d.getRecruitmentReason());
        r.setRecruitmentReasonLabel(RecruitmentReasonHelper.getLabel(d.getRecruitmentReason()));
        r.setUrgencyLevelId(d.getUrgencyLevelId());
        r.setUrgencyLevelLabel(resolveListLabel(d.getUrgencyLevelId()));
        r.setCspCategoryId(d.getCspCategoryId());
        r.setCspCategoryLabel(resolveListLabel(d.getCspCategoryId()));
        r.setExperienceLevelId(d.getExperienceLevelId());
        r.setExperienceLevelLabel(resolveListLabel(d.getExperienceLevelId()));
        r.setExperienceLevelCode(resolveListCode(d.getExperienceLevelId()));
        r.setEducationLevelId(d.getEducationLevelId());
        r.setEducationLevelLabel(resolveListLabel(d.getEducationLevelId()));
        r.setTechnicalSkills(fromJson(d.getTechnicalSkillsJson()));
        r.setSoftSkills(fromJson(d.getSoftSkillsJson()));
        r.setTargetStartDate(d.getTargetStartDate());
        r.setHeadcount(d.getHeadcount());
        r.setBudgetRange(d.getBudgetRange());
        r.setAdditionalNotes(d.getAdditionalNotes());
        r.setStatut(d.getStatut());
        r.setSubmittedAt(d.getSubmittedAt());
        r.setReviewedByUserId(d.getReviewedByUserId());
        r.setReviewedAt(d.getReviewedAt());
        r.setReviewComment(d.getReviewComment());
        r.setCandidateCount(d.getCandidateCount());
        r.setCreatedAt(d.getCreatedAt());
        r.setUpdatedAt(d.getUpdatedAt());
        return r;
    }

    private RecruitmentDemandSummary toSummary(RecruitmentDemand d) {
        RecruitmentDemandSummary s = new RecruitmentDemandSummary();
        s.setId(d.getId());
        s.setJobTitle(d.getJobTitle());
        s.setJobExactTitle(d.getJobExactTitle());
        s.setDepartment(d.getDepartment());
        s.setStatut(d.getStatut());
        s.setUrgencyLevelLabel(resolveListLabel(d.getUrgencyLevelId()));
        s.setRecruitmentReason(d.getRecruitmentReason());
        s.setRecruitmentReasonLabel(RecruitmentReasonHelper.getLabel(d.getRecruitmentReason()));
        s.setHeadcount(d.getHeadcount());
        s.setCandidateCount(d.getCandidateCount());
        s.setSubmittedAt(d.getSubmittedAt());
        s.setCreatedByUserId(d.getCreatedByUserId());
        return s;
    }

    /**
     * A dimension row's French label (`grades` / `disciplines`).
     *
     * JdbcTemplate rather than the repositories: this service has no Grade/Discipline repo and
     * adding two purely to read one column each would be more plumbing than the read is worth.
     * The table name is NEVER caller-supplied — only the two literals above reach it.
     */
    private String resolveDimensionLabel(String table, Long id) {
        if (id == null) return null;
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT label_fr FROM [dbo].[" + table + "] WHERE id = ?", String.class, id);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.debug("Could not read {} label for id {}: {}", table, id, ex.getMessage());
            return null;
        }
    }

    /** A list value's stable `value_code` — the safe key for clients to branch on. */
    private String resolveListCode(Long valueId) {
        if (valueId == null) return null;
        return listValueRepo.findById(valueId)
                .map(com.daf360.rh.lists.ConfigurableListValue::getValueCode)
                .orElse(null);
    }

    private String resolveListLabel(Long valueId) {
        if (valueId == null) return null;
        return listValueRepo.findById(valueId)
                .map(v -> v.getLabelFr() != null ? v.getLabelFr() : v.getLabelEn())
                .orElse(null);
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception ex) {
            log.warn("Failed to serialize list to JSON: {}", ex.getMessage());
            return null;
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            log.warn("Failed to deserialize list from JSON: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
