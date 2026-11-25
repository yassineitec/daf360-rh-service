package com.daf360.rh.service;

import com.daf360.rh.domain.RecruitmentDemand;
import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.dto.recruitment.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.lists.ConfigurableListTypeRepository;
import com.daf360.rh.lists.ConfigurableListValueRepository;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.RecruitmentDemandRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;
    private final JdbcTemplate  jdbc;
    private final ObjectMapper  objectMapper;
    private final com.daf360.rh.security.TenantService tenantService;
    /** V137: posts filled, close / reopen, headcount changes. */
    private final DemandFillService fillService;
    /** The « Historique » of a demand, assembled from what is already recorded. */
    private final DemandHistoryService historyService;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};


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

        checkPaysInScope(request.getPaysId());

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

        dispatch("RECRUITMENT_DEMAND_CREATED", demand, actorUserId);

        return toResponse(demand);
    }

    public RecruitmentDemandResponse review(Long id, ReviewRecruitmentDemandRequest request, Long actorUserId) {
        RecruitmentDemand demand = findOrThrow(id);

        if (demand.getStatut() != RecruitmentDemandStatus.EN_ATTENTE) {
            throw new AppException(ErrorCode.RECRUITMENT_DEMAND_ALREADY_REVIEWED);
        }
        // Four-eyes: a DRH or an administrator can file a request AND holds the approval
        // permission, which let them validate their own request in one click.
        if (actorUserId != null && actorUserId.equals(demand.getCreatedByUserId())) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Vous ne pouvez pas valider ou refuser votre propre demande de recrutement.");
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

        // Both outcomes are a validation step: the requester and HR must hear about a refusal
        // as much as an approval — before, a rejected request was only discoverable in the list.
        dispatch(newStatut == RecruitmentDemandStatus.APPROUVEE
                        ? "RECRUITMENT_DEMAND_APPROVED"
                        : "RECRUITMENT_DEMAND_REJECTED",
                demand, actorUserId);

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

        // The approvers and HR were told it was waiting; tell them it no longer is.
        dispatch("RECRUITMENT_DEMAND_CANCELLED", demand, actorUserId);

        return toResponse(demand);
    }

    @Transactional(readOnly = true)
    public Page<RecruitmentDemandSummary> listByPays(List<Long> requestedPaysIds, RecruitmentDemandStatus statut,
                                                     Pageable pageable) {
        // Drapeaux de la navbar croisés avec le périmètre du rôle ; rien de demandé = tout ce
        // périmètre ; null = rôle « Tous les pays » (aucun filtre).
        List<Long> paysIds = tenantService.resolvePaysList(requestedPaysIds);
        Pageable sorted = withDefaultSort(pageable);
        Page<RecruitmentDemand> page;
        if (paysIds == null) {
            page = (statut != null) ? demandRepo.findByStatut(statut, sorted) : demandRepo.findAll(sorted);
        } else {
            page = (statut != null)
                    ? demandRepo.findByPaysIdInAndStatut(paysIds, statut, sorted)
                    : demandRepo.findByPaysIdIn(paysIds, sorted);
        }
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public Page<RecruitmentDemandSummary> listMine(Long userId, RecruitmentDemandStatus statut, Pageable pageable) {
        Pageable sorted = withDefaultSort(pageable);
        Page<RecruitmentDemand> page = (statut != null)
                ? demandRepo.findByCreatedByUserIdAndStatut(userId, statut, sorted)
                : demandRepo.findByCreatedByUserId(userId, sorted);
        return page.map(this::toSummary);
    }

    /** The caller's ?sort= when it sent one, else the historical order: newest submission first. */
    private static Pageable withDefaultSort(Pageable pageable) {
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "submittedAt"));
    }

    @Transactional(readOnly = true)
    public RecruitmentDemandResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<ApprovedDemandOption> getApprovedOptions(Long paysId) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;
        List<RecruitmentDemand> approved =
                demandRepo.findByPaysIdAndStatutOrderByJobTitleAsc(resolvedPaysId, RecruitmentDemandStatus.APPROUVEE);
        if (approved.isEmpty()) return List.of();

        // Same post = same key as the job library. Every demand of the entity, any status, so a
        // post raised, filled and closed long ago still counts as « already requested ».
        Map<String, List<Long>> idsByPost = new java.util.HashMap<>();
        for (RecruitmentDemand d : demandRepo.findAllByPaysId(resolvedPaysId)) {
            idsByPost.computeIfAbsent(postKey(d), k -> new java.util.ArrayList<>()).add(d.getId());
        }
        Map<Long, Integer> archivedByDemand = archivedCountByDemand(resolvedPaysId);

        return approved.stream()
                .map(d -> {
                    List<Long> others = otherDemandsOfPost(idsByPost, d);
                    // The demand's OWN archived candidatures count too: a demand closed (which
                    // archives them) and then reopened must offer them back.
                    int archived = archivedByDemand.getOrDefault(d.getId(), 0)
                            + others.stream().mapToInt(id -> archivedByDemand.getOrDefault(id, 0)).sum();
                    return new ApprovedDemandOption(d.getId(), d.getJobTitle(), d.getDepartment(),
                            d.getHeadcount(), d.getPositionsFilled(), others.size(), archived);
                })
                .toList();
    }

    /**
     * The ARCHIVED candidatures of this demand (a closed-then-reopened demand archived its own)
     * and of the OTHER demands raised for the same post — what the creation wizard offers to copy
     * from. Newest archive first.
     */
    @Transactional(readOnly = true)
    public List<com.daf360.rh.dto.pipeline.ArchivedCandidateDto> relatedArchivedCandidates(Long demandId) {
        RecruitmentDemand demand = findOrThrow(demandId);
        checkPaysInScope(demand.getPaysId());

        Map<String, List<Long>> idsByPost = new java.util.HashMap<>();
        for (RecruitmentDemand d : demandRepo.findAllByPaysId(demand.getPaysId())) {
            idsByPost.computeIfAbsent(postKey(d), k -> new java.util.ArrayList<>()).add(d.getId());
        }
        List<Long> others = new java.util.ArrayList<>(otherDemandsOfPost(idsByPost, demand));
        others.add(demand.getId());

        String in = String.join(",", Collections.nCopies(others.size(), "?"));
        String sql = """
            SELECT TOP 100
                   c.id, c.first_name, c.last_name, c.gender, c.applied_position, c.email_personal,
                   c.recruitment_demand_id AS demand_id,
                   COALESCE(NULLIF(LTRIM(RTRIM(rd.job_exact_title)), ''), rd.job_title) AS demand_title,
                   c.archive_reason, c.archive_note, c.archived_from_status, c.rejection_reason,
                   CONVERT(datetime2, COALESCE(c.archived_at, c.updated_at, c.created_at)) AS archived_at,
                   u.fullName AS archived_by_name
              FROM [dbo].[candidates] c
              LEFT JOIN [dbo].[recruitment_demands] rd ON rd.id = c.recruitment_demand_id
              LEFT JOIN [dbo].[Users] u ON u.id = c.archived_by
             WHERE c.status = 'ARCHIVED' AND c.recruitment_demand_id IN (""" + in + """
            )
             ORDER BY COALESCE(c.archived_at, c.updated_at, c.created_at) DESC
            """;
        return jdbc.queryForList(sql, others.toArray()).stream().map(r -> {
            String first = r.get("first_name") != null ? r.get("first_name").toString() : "";
            String last  = r.get("last_name")  != null ? r.get("last_name").toString()  : "";
            Object at = r.get("archived_at");
            return new com.daf360.rh.dto.pipeline.ArchivedCandidateDto(
                    ((Number) r.get("id")).longValue(),
                    (first + " " + last).trim(),
                    str(r.get("gender")),
                    str(r.get("applied_position")),
                    r.get("demand_id") != null ? ((Number) r.get("demand_id")).longValue() : null,
                    str(r.get("demand_title")),
                    str(r.get("archive_reason")),
                    str(r.get("archive_note")),
                    str(r.get("archived_from_status")),
                    at instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toString() : str(at),
                    str(r.get("archived_by_name")),
                    str(r.get("rejection_reason")),
                    str(r.get("email_personal")));
        }).toList();
    }

    /**
     * The demand's history, newest first: its own life (created, reviewed, resized, closed,
     * reopened), its candidatures (joined, copied from an archived one, offer agreed, archived,
     * refused) and the other demands for the same post. Scoped like the demand itself.
     */
    @Transactional(readOnly = true)
    public List<DemandHistoryEvent> history(Long demandId) {
        RecruitmentDemand demand = findOrThrow(demandId);
        checkPaysInScope(demand.getPaysId());
        String key = postKey(demand);
        List<RecruitmentDemand> samePost = demandRepo.findAllByPaysId(demand.getPaysId()).stream()
                .filter(d -> key.equals(postKey(d)))
                .toList();
        return historyService.history(demand, samePost);
    }

    /**
     * Every candidature of one demand, newest first — the demand page's « Candidats » section and
     * KPI row. Scoped like the demand itself.
     */
    @Transactional(readOnly = true)
    public List<DemandCandidateDto> candidatesOf(Long demandId) {
        RecruitmentDemand demand = findOrThrow(demandId);
        checkPaysInScope(demand.getPaysId());
        boolean filled = DemandFillService.isFilled(demand);
        String sql = """
            SELECT c.id, c.first_name, c.last_name, c.gender, c.email_personal, c.status,
                   CONVERT(datetime2, c.created_at) AS created_at,
                   CONVERT(date, DATEADD(day, ?, c.created_at)) AS due_date,
                   c.archive_reason, c.archived_from_status, c.rejection_reason,
                   CONVERT(datetime2, c.archived_at) AS archived_at
              FROM [dbo].[candidates] c
             WHERE c.recruitment_demand_id = ?
             ORDER BY c.created_at DESC
            """;
        return jdbc.queryForList(sql, DemandFillService.ARCHIVE_AFTER_DAYS, demandId).stream().map(r -> {
            String first  = r.get("first_name") != null ? r.get("first_name").toString() : "";
            String last   = r.get("last_name")  != null ? r.get("last_name").toString()  : "";
            String status = str(r.get("status"));
            boolean counting = filled && status != null
                    && DemandFillService.STILL_RECRUITING.stream().anyMatch(s -> s.name().equals(status));
            return new DemandCandidateDto(
                    ((Number) r.get("id")).longValue(),
                    (first + " " + last).trim(),
                    str(r.get("gender")),
                    str(r.get("email_personal")),
                    status,
                    isoLocal(r.get("created_at")),
                    str(r.get("archive_reason")),
                    str(r.get("archived_from_status")),
                    isoLocal(r.get("archived_at")),
                    counting && r.get("due_date") != null ? r.get("due_date").toString().substring(0, 10) : null,
                    str(r.get("rejection_reason")));
        }).toList();
    }

    private static String isoLocal(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toString();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toString();
        return v != null ? v.toString() : null;
    }

    private static List<Long> otherDemandsOfPost(Map<String, List<Long>> idsByPost, RecruitmentDemand d) {
        return idsByPost.getOrDefault(postKey(d), List.of()).stream()
                .filter(id -> !id.equals(d.getId()))
                .toList();
    }

    /** demand id → number of ARCHIVED candidatures, for one entity. */
    private Map<Long, Integer> archivedCountByDemand(Long paysId) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT c.recruitment_demand_id AS demand_id, COUNT(*) AS n
                  FROM [dbo].[candidates] c
                  JOIN [dbo].[recruitment_demands] rd ON rd.id = c.recruitment_demand_id
                 WHERE c.status = 'ARCHIVED' AND rd.pays_id = ?
                 GROUP BY c.recruitment_demand_id
                """, rs -> { out.put(rs.getLong("demand_id"), rs.getInt("n")); }, paysId);
        return out;
    }

    private static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    // ── Posts filled (V137) ──────────────────────────────────────────────────

    /** RH closes the demand by hand; its candidatures still in recruitment are archived. */
    public RecruitmentDemandResponse close(Long id, String note, Long actorUserId) {
        return toResponse(fillService.closeManually(id, note, actorUserId));
    }

    /** Reopens a closed demand that still has a post to fill. */
    public RecruitmentDemandResponse reopen(Long id, Long actorUserId) {
        return toResponse(fillService.reopenManually(id, actorUserId));
    }

    /** Changes the number of posts — never below the posts already filled. */
    public RecruitmentDemandResponse changeHeadcount(Long id, int headcount, Long actorUserId) {
        return toResponse(fillService.changeHeadcount(id, headcount, actorUserId));
    }

    /** Demands that became real positions — the ones worth reusing as a template. */
    private static final List<RecruitmentDemandStatus> LIBRARY_STATUSES =
            List.of(RecruitmentDemandStatus.APPROUVEE, RecruitmentDemandStatus.CLOTUREE);

    /**
     * « Bibliothèque des postes » — the entity's past positions, one entry per post.
     *
     * A post is department + job title + exact title (case and spaces ignored): the same
     * vacancy raised five times is one entry « demandé 5 fois », carrying the values of its
     * most recent demand, which is what a manager wants to start again from. Only approved and
     * closed demands: a pending, rejected or cancelled one never became a position.
     *
     * Read-only and gated like creating a demand (RH_CREATE_RECRUITMENT_DEMAND): it exists to
     * prefill a new one. The pays must be in the caller's scope, as on create.
     */
    @Transactional(readOnly = true)
    public List<JobLibraryEntry> library(Long paysId) {
        checkPaysInScope(paysId);
        List<RecruitmentDemand> rows =
                demandRepo.findTop300ByPaysIdAndStatutInOrderBySubmittedAtDesc(paysId, LIBRARY_STATUSES);

        // Insertion order = newest first, so the first demand seen for a post is its latest.
        Map<String, List<RecruitmentDemand>> byPost = new java.util.LinkedHashMap<>();
        for (RecruitmentDemand d : rows) {
            byPost.computeIfAbsent(postKey(d), k -> new java.util.ArrayList<>()).add(d);
        }
        return byPost.values().stream()
                .map(group -> {
                    RecruitmentDemand latest = group.get(0);
                    int filled = group.stream().mapToInt(RecruitmentDemand::getPositionsFilled).sum();
                    // A demand of this post still recruiting: the manager is warned before filing
                    // a duplicate of it (newest first, so the first open one is the latest).
                    RecruitmentDemand open = group.stream()
                            .filter(d -> d.getStatut() == RecruitmentDemandStatus.APPROUVEE && d.remainingPositions() > 0)
                            .findFirst().orElse(null);
                    return new JobLibraryEntry(
                            toResponse(latest),
                            group.size(),
                            latest.getSubmittedAt(),
                            resolveDepartmentLabelEn(latest.getDepartmentId(), latest.getDepartment()),
                            filled,
                            open != null ? open.getId() : null,
                            open != null ? open.remainingPositions() : 0);
                })
                .toList();
    }

    private static String postKey(RecruitmentDemand d) {
        String dept = d.getDepartmentId() != null
                ? "#" + d.getDepartmentId()
                : norm(d.getDepartment());
        return dept + "|" + norm(d.getJobTitle()) + "|" + norm(d.getJobExactTitle());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
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
    /**
     * A request may only be filed for a pays the caller can see. paysId comes from the client,
     * and recipients are resolved per pays — a wrong value notified another entity's approvers.
     * An unresolved scope stays permissive, as everywhere else (see PaysScopeContext.Scope).
     */
    private void checkPaysInScope(Long paysId) {
        var scope = tenantService.getPaysScope();
        if (paysId == null || scope.unfiltered()) return;
        if (!scope.paysIds().contains(paysId)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Vous ne pouvez pas créer une demande de recrutement pour ce pays.");
        }
    }

    private static final String USER_NAME_SQL =
            "SELECT COALESCE(u.fullName, u.username, u.email) FROM [dbo].[Users] u WHERE u.id = ?";

    private String userName(Long userId) {
        if (userId == null) return "";
        try {
            List<String> rows = jdbc.queryForList(USER_NAME_SQL, String.class, userId);
            return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * Raises a recruitment-demand event through the routing engine.
     *
     * Replaces the pair of hand-written senders this class used to carry: an in-app helper
     * that resolved holders of a hardcoded permission, and an e-mail helper that built its own
     * HTML. Both are now the rule's business, so an admin can retune recipients and wording
     * without a deploy — and there is one code path instead of two that could disagree.
     *
     * - subject = the requester, so a rule can address "the person who asked" (SUBJECT mode);
     *   it was never set, so the requester could only be reached through a broad permission.
     * - actor = whoever triggered the step; excluded from the audience.
     * - after commit: the class is @Transactional, and dispatching inside it could announce a
     *   request whose commit then failed.
     */
    private void dispatch(String eventCode, RecruitmentDemand demand, Long actorUserId) {
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("jobTitle",      demand.getJobTitle() != null ? demand.getJobTitle() : "");
        vars.put("department",    demand.getDepartment() != null ? demand.getDepartment() : "");
        vars.put("headcount",     String.valueOf(demand.getHeadcount()));
        vars.put("urgency",       nullToEmpty(resolveListLabel(demand.getUrgencyLevelId())));
        vars.put("requesterName", userName(demand.getCreatedByUserId()));
        vars.put("reviewerName",  userName(demand.getReviewedByUserId()));
        vars.put("actorName",     userName(actorUserId));
        vars.put("comment",       nullToEmpty(demand.getReviewComment()));

        notificationRoutingService.dispatchAfterCommit(RoutingContext.builder()
                .eventCode(eventCode)
                .paysId(demand.getPaysId())
                .subjectUserId(demand.getCreatedByUserId())
                .actorUserId(actorUserId)
                .entityType(NotificationEntityType.RECRUITMENT_DEMAND)
                .entityId(demand.getId())
                .templateVars(vars)
                .build());
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
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
        r.setUrgencyLevelCode(resolveListCode(d.getUrgencyLevelId()));
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
        r.setPositionsFilled(d.getPositionsFilled());
        r.setHiredCount(d.getHiredCount());
        r.setRemainingPositions(d.remainingPositions());
        r.setClosedAt(d.getClosedAt());
        r.setClosedReason(d.getClosedReason());
        r.setClosedByName(d.getClosedBy() != null ? userName(d.getClosedBy()) : null);
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
        s.setDepartmentLabelEn(resolveDepartmentLabelEn(d.getDepartmentId(), d.getDepartment()));
        s.setStatut(d.getStatut());
        s.setUrgencyLevelLabel(resolveListLabel(d.getUrgencyLevelId()));
        s.setRecruitmentReason(d.getRecruitmentReason());
        s.setRecruitmentReasonLabel(RecruitmentReasonHelper.getLabel(d.getRecruitmentReason()));
        s.setHeadcount(d.getHeadcount());
        s.setCandidateCount(d.getCandidateCount());
        s.setPositionsFilled(d.getPositionsFilled());
        s.setHiredCount(d.getHiredCount());
        s.setClosedReason(d.getClosedReason());
        s.setSubmittedAt(d.getSubmittedAt());
        s.setCreatedByUserId(d.getCreatedByUserId());
        return s;
    }

    /**
     * The department's English label, for the list's department filter in English.
     *
     * By `department_id` when the demand has one (V72+); older demands only carry the French
     * label, so those are matched on `label_fr`. Null when nothing matches — the client then
     * falls back to the stored French label.
     */
    private String resolveDepartmentLabelEn(Long departmentId, String labelFr) {
        try {
            List<String> rows = departmentId != null
                    ? jdbc.queryForList("SELECT label_en FROM [dbo].[departments] WHERE id = ?",
                            String.class, departmentId)
                    : (labelFr == null || labelFr.isBlank())
                        ? List.of()
                        : jdbc.queryForList("SELECT TOP 1 label_en FROM [dbo].[departments] WHERE label_fr = ?",
                            String.class, labelFr);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
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
