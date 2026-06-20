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
        validateListValue(request.getUrgencyLevelId(), "URGENCY_LEVEL");
        if (request.getCspCategoryId()    != null) validateListValue(request.getCspCategoryId(),    "CSP_CATEGORY");
        if (request.getExperienceLevelId() != null) validateListValue(request.getExperienceLevelId(), "EXPERIENCE_LEVEL");
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
                .urgencyLevelId(request.getUrgencyLevelId())
                .recruitmentReason(request.getRecruitmentReason())
                .cspCategoryId(request.getCspCategoryId())
                .experienceLevelId(request.getExperienceLevelId())
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
        Page<RecruitmentDemand> page = (statut != null)
                ? demandRepo.findByPaysIdAndStatutOrderBySubmittedAtDesc(paysId, statut, pageable)
                : demandRepo.findByPaysIdOrderBySubmittedAtDesc(paysId, pageable);
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
        return demandRepo.findByPaysIdAndStatutOrderByJobTitleAsc(paysId, RecruitmentDemandStatus.APPROUVEE)
                .stream()
                .map(d -> new ApprovedDemandOption(d.getId(), d.getJobTitle(), d.getDepartment()))
                .toList();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private RecruitmentDemand findOrThrow(Long id) {
        return demandRepo.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RECRUITMENT_DEMAND_NOT_FOUND));
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
