package com.daf360.rh.service;

import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.*;
import com.daf360.rh.dto.offboarding.*;
import com.daf360.rh.dto.profile.LifecycleTransitionDto;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OffboardingWorkflowService {

    private static final String INSERT_NOTIF_SQL =
        "INSERT INTO [dbo].[notifications] (user_id, module, title, message, is_read, created_at) " +
        "VALUES (?, ?, ?, ?, 0, SYSDATETIMEOFFSET())";

    private static final String USERS_WITH_PERM_SQL =
        "SELECT u.id, COALESCE(u.username, u.email) as email " +
        "FROM [dbo].[Users] u " +
        "JOIN [dbo].[RolePermissions] rp ON u.role_id = rp.role_id " +
        "WHERE rp.permission = ? AND u.pays_id = ? " +
        "AND (u.isActive = 1 OR u.isActive IS NULL)";

    private static final String CONTRACT_TYPE_SQL =
        "SELECT contract_type_code FROM [dbo].[employee_contracts] WHERE id = ?";

    private static final String EMPLOYEE_NAME_SQL =
        "SELECT CONCAT(c.first_name, ' ', c.last_name) " +
        "FROM [dbo].[candidates] c " +
        "JOIN [dbo].[employee_profiles] ep ON ep.candidate_id = c.id " +
        "WHERE ep.id = ?";

    private static final List<String> ACTIVE_STATUSES =
        List.of("PENDING", "IN_PROGRESS", "BLOCKED");

    private final OffboardingWorkflowInstanceRepository instanceRepo;
    private final OffboardingTaskRepository             taskRepo;
    private final OffboardingAssetReturnRepository      assetRepo;
    private final ExitInterviewRepository               interviewRepo;
    private final OffboardingTaskCatalogRepository      catalogRepo;
    private final EmployeeProfileService                profileService;
    private final ItProvisioningRepository              itProvisioningRepo;
    private final ItAssetRepository                     itAssetRepo;
    private final AuditService                          auditService;
    private final MailService                           mailService;
    private final JdbcTemplate                         jdbc;
    private final ObjectMapper                         objectMapper;
    private final com.daf360.rh.security.TenantService tenantService;

    // ── 1. Start offboarding ──────────────────────────────────────────────────

    public OffboardingWorkflowInstanceDto startOffboarding(
            StartOffboardingRequestDto request, Long initiatedBy) {

        Long profileId = request.getEmployeeProfileId();

        // Guard: no active workflow already running
        instanceRepo.findByEmployeeProfileIdAndStatusNotIn(profileId, List.of("CANCELLED", "ARCHIVED", "VALIDATED"))
            .ifPresent(existing -> {
                throw new AppException(ErrorCode.ALREADY_EXISTS,
                    "Un workflow d'offboarding actif existe déjà pour ce profil (id=" + existing.getId() + ")");
            });

        // Resolve pays — admin users bypass TenantContext, fall back to the profile's own paysId
        Long paysId = tenantService.getEffectivePaysId();
        if (paysId == null) {
            paysId = profileService.getProfilePaysId(profileId);
        }

        // Resolve contract type for catalog lookup
        String contractType = "CDI"; // default fallback
        if (request.getContractId() != null) {
            try {
                contractType = resolveContractType(request.getContractId());
            } catch (Exception e) {
                log.warn("Could not resolve contract type for contractId={}, using default CDI", request.getContractId());
            }
        }

        // Transition profile to OFFBOARDING (only if currently ACTIVE)
        try {
            LifecycleTransitionDto transitionDto = new LifecycleTransitionDto();
            transitionDto.setNewStatus(LifecycleStatus.OFFBOARDING);
            transitionDto.setReason("Offboarding initié — " + request.getDepartureReason());
            profileService.transitionLifecycleByActor(profileId, transitionDto, initiatedBy);
        } catch (AppException ex) {
            log.warn("Lifecycle transition to OFFBOARDING skipped for profileId={}: {}", profileId, ex.getMessage());
        }

        // Resolve handover manager user ID before creating tasks
        Long managerUserId = null;
        if (request.getHandoverManagerProfileId() != null) {
            try {
                managerUserId = profileService.getUserId(request.getHandoverManagerProfileId());
            } catch (Exception ex) {
                log.warn("Could not resolve userId for handover manager profileId={}: {}",
                    request.getHandoverManagerProfileId(), ex.getMessage());
            }
        }

        // Create workflow instance
        OffsetDateTime now = OffsetDateTime.now();
        OffboardingWorkflowInstance instance = OffboardingWorkflowInstance.builder()
            .paysId(paysId)
            .employeeProfileId(profileId)
            .contractId(request.getContractId())
            .triggerDate(request.getTriggerDate())
            .lastWorkingDay(request.getLastWorkingDay())
            .departureReason(request.getDepartureReason())
            .departureNotes(request.getDepartureNotes())
            .handoverManagerProfileId(request.getHandoverManagerProfileId())
            .status("IN_PROGRESS")
            .initiatedBy(initiatedBy)
            .slaBreachFlag(false)
            .createdAt(now)
            .build();

        final OffboardingWorkflowInstance savedInstance = instanceRepo.save(instance);

        // Create tasks from catalog
        List<OffboardingTaskCatalog> catalogTasks =
            catalogRepo.findByPaysIdAndContractTypeAndIsActiveTrueOrderByOrderIndexAsc(
                savedInstance.getPaysId(), contractType);

        if (catalogTasks.isEmpty()) {
            log.warn("No catalog tasks found for paysId={} contractType={} — workflow created with no tasks",
                savedInstance.getPaysId(), contractType);
        }

        final Long instanceId = savedInstance.getId();
        final Long finalManagerUserId = managerUserId;

        List<OffboardingTask> tasks = catalogTasks.stream()
            .map(cat -> {
                OffboardingTask.OffboardingTaskBuilder builder = OffboardingTask.builder()
                    .workflowInstance(savedInstance)
                    .taskCode(cat.getTaskCode())
                    .taskLabel(cat.getTaskLabel())
                    .ownerRole(cat.getOwnerRole())
                    .isMandatory(cat.getIsMandatory())
                    .isBlocking(cat.getIsBlocking())
                    .dueDate(calculateDueDate(request.getTriggerDate(), cat.getSlaWorkingDays()))
                    .status("PENDING")
                    .createdAt(now);
                // Pre-assign KNOWLEDGE_TRANSFER task to the handover manager
                if ("KNOWLEDGE_TRANSFER".equals(cat.getTaskCode()) && finalManagerUserId != null) {
                    builder.ownerUserId(finalManagerUserId);
                }
                return builder.build();
            })
            .collect(Collectors.toList());

        taskRepo.saveAll(tasks);
        savedInstance.setTasks(tasks);

        // Seed IT asset returns from the employee's provisioning record
        seedItAssetReturns(savedInstance, profileId);

        String employeeName = resolveEmployeeName(profileId);
        String nameDisplay = employeeName != null ? employeeName : "profil id=" + profileId;

        auditService.log(initiatedBy != null ? initiatedBy.toString() : "SYSTEM",
            "OFFBOARDING_STARTED", "OffboardingWorkflowInstance", instanceId,
            null, "profileId=" + profileId + " reason=" + request.getDepartureReason());

        notifyTaskOwners(savedInstance.getPaysId(),
            "Offboarding initié",
            "Un processus d'offboarding a été initié pour " + nameDisplay + ".",
            PermissionCatalog.RH_MANAGE_OFFBOARDING);

        return toInstanceDto(savedInstance);
    }

    // ── 2. Complete a task ────────────────────────────────────────────────────

    public OffboardingTaskDto completeTask(Long taskId, CompleteTaskRequestDto request,
                                           Long completedBy) {
        OffboardingTask task = findTaskOrThrow(taskId);
        if ("DONE".equals(task.getStatus()) || "SKIPPED".equals(task.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "La tâche est déjà dans le statut " + task.getStatus());
        }

        task.setStatus("DONE");
        task.setCompletedBy(completedBy);
        task.setCompletedAt(OffsetDateTime.now());
        task.setComments(request.getComments());
        task.setAttachedDocumentUrl(request.getAttachedDocumentUrl());
        taskRepo.save(task);

        // Advance workflow status if it was BLOCKED
        OffboardingWorkflowInstance instance = task.getWorkflowInstance();
        if ("BLOCKED".equals(instance.getStatus())) {
            boolean stillBlocked = !taskRepo.findBlockingIncomplete(instance.getId()).isEmpty();
            if (!stillBlocked) {
                instance.setStatus("IN_PROGRESS");
                instance.setUpdatedAt(OffsetDateTime.now());
                instanceRepo.save(instance);
            }
        }

        auditService.log(completedBy != null ? completedBy.toString() : "SYSTEM",
            "OFFBOARDING_TASK_DONE", "OffboardingTask", taskId,
            null, "workflowId=" + instance.getId());

        return toTaskDto(task);
    }

    // ── 3. Skip a task ────────────────────────────────────────────────────────

    public OffboardingTaskDto skipTask(Long taskId, String reason, Long skippedBy) {
        OffboardingTask task = findTaskOrThrow(taskId);

        if (Boolean.TRUE.equals(task.getIsBlocking())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Les tâches bloquantes ne peuvent pas être ignorées.");
        }
        if ("DONE".equals(task.getStatus()) || "SKIPPED".equals(task.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "La tâche est déjà dans le statut " + task.getStatus());
        }

        task.setStatus("SKIPPED");
        task.setSkippedBy(skippedBy);
        task.setSkipReason(reason);
        taskRepo.save(task);

        auditService.log(skippedBy != null ? skippedBy.toString() : "SYSTEM",
            "OFFBOARDING_TASK_SKIPPED", "OffboardingTask", taskId,
            null, "reason=" + reason);

        return toTaskDto(task);
    }

    // ── 4. Validate workflow ──────────────────────────────────────────────────

    public OffboardingWorkflowInstanceDto validateWorkflow(Long instanceId, Long validatedBy) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        if (!List.of("IN_PROGRESS", "BLOCKED").contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Seuls les workflows IN_PROGRESS ou BLOCKED peuvent être validés.");
        }

        // Check blocking tasks
        List<OffboardingTask> blockingPending = taskRepo.findBlockingIncomplete(instanceId);
        if (!blockingPending.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                blockingPending.size() + " tâche(s) bloquante(s) non complétée(s). Validation impossible.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        instance.setStatus("VALIDATED");
        instance.setValidatedBy(validatedBy);
        instance.setValidatedAt(now);
        instance.setCompletionDate(now);
        instance.setUpdatedAt(now);
        instanceRepo.save(instance);

        // Transition profile OFFBOARDING → TERMINATED
        LifecycleTransitionDto transitionDto = new LifecycleTransitionDto();
        transitionDto.setNewStatus(LifecycleStatus.TERMINATED);
        transitionDto.setReason("Offboarding validé (workflow id=" + instanceId + ")");
        profileService.transitionLifecycleByActor(
            instance.getEmployeeProfileId(), transitionDto, validatedBy);

        auditService.log(validatedBy != null ? validatedBy.toString() : "SYSTEM",
            "OFFBOARDING_VALIDATED", "OffboardingWorkflowInstance", instanceId,
            null, "profileId=" + instance.getEmployeeProfileId());

        return toInstanceDto(instance);
    }

    // ── 5. Cancel workflow ────────────────────────────────────────────────────

    public OffboardingWorkflowInstanceDto cancelWorkflow(Long instanceId, String reason,
                                                          Long cancelledBy) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        if (!ACTIVE_STATUSES.contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Seuls les workflows actifs (PENDING/IN_PROGRESS/BLOCKED) peuvent être annulés.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        instance.setStatus("CANCELLED");
        instance.setCancelledBy(cancelledBy);
        instance.setCancelledAt(now);
        instance.setCancellationReason(reason);
        instance.setUpdatedAt(now);
        instanceRepo.save(instance);

        // Revert profile OFFBOARDING → ACTIVE
        LifecycleTransitionDto transitionDto = new LifecycleTransitionDto();
        transitionDto.setNewStatus(LifecycleStatus.ACTIVE);
        transitionDto.setReason("Offboarding annulé — " + reason);
        profileService.transitionLifecycleByActor(
            instance.getEmployeeProfileId(), transitionDto, cancelledBy);

        auditService.log(cancelledBy != null ? cancelledBy.toString() : "SYSTEM",
            "OFFBOARDING_CANCELLED", "OffboardingWorkflowInstance", instanceId,
            null, "reason=" + reason);

        return toInstanceDto(instance);
    }

    // ── 6. Save exit interview ────────────────────────────────────────────────

    public ExitInterviewDto saveExitInterview(Long instanceId, ExitInterviewRequestDto request,
                                              Long conductedBy) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        // One interview per workflow
        interviewRepo.findByWorkflowInstanceId(instanceId).ifPresent(existing -> {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Un entretien de sortie existe déjà pour ce workflow (id=" + existing.getId() + ")");
        });

        // Serialize departureReasons list to JSON
        String reasonsJson = null;
        if (request.getDepartureReasons() != null && !request.getDepartureReasons().isEmpty()) {
            try {
                reasonsJson = objectMapper.writeValueAsString(request.getDepartureReasons());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize departureReasons: {}", e.getMessage());
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        ExitInterview interview = ExitInterview.builder()
            .workflowInstance(instance)
            .conductedBy(conductedBy)
            .conductedDate(request.getConductedDate())
            .departureReasons(reasonsJson)
            .feedbackText(request.getFeedbackText())
            .isAnonymised(false)
            .createdAt(now)
            .build();

        interview = interviewRepo.save(interview);

        // Mark EXIT_INTERVIEW task as DONE if present
        taskRepo.findByWorkflowInstanceId(instanceId).stream()
            .filter(t -> "EXIT_INTERVIEW".equals(t.getTaskCode())
                      && !"DONE".equals(t.getStatus())
                      && !"SKIPPED".equals(t.getStatus()))
            .findFirst()
            .ifPresent(t -> {
                t.setStatus("DONE");
                t.setCompletedBy(conductedBy);
                t.setCompletedAt(now);
                t.setComments("Entretien de sortie enregistré");
                taskRepo.save(t);
            });

        auditService.log(conductedBy != null ? conductedBy.toString() : "SYSTEM",
            "EXIT_INTERVIEW_SAVED", "ExitInterview", interview.getId(),
            null, "workflowId=" + instanceId);

        return toInterviewDto(interview);
    }

    // ── 7. Get workflow instance ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OffboardingWorkflowInstanceDto getWorkflowInstance(Long instanceId) {
        return toInstanceDto(findInstanceOrThrow(instanceId));
    }

    // ── 8. List workflow instances ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OffboardingWorkflowInstanceDto> listWorkflowInstances(Long paysId,
                                                                       String status) {
        Long effectivePaysId = tenantService.getEffectivePaysId();
        Long resolvedPaysId  = effectivePaysId != null ? effectivePaysId : paysId;

        List<OffboardingWorkflowInstance> instances;
        if (status != null && !status.isBlank()) {
            instances = resolvedPaysId != null
                ? instanceRepo.findByPaysIdAndStatus(resolvedPaysId, status)
                : instanceRepo.findByStatus(status);
        } else {
            instances = resolvedPaysId != null
                ? instanceRepo.findActiveByPays(resolvedPaysId)
                : instanceRepo.findAllActive();
        }
        return instances.stream().map(this::toInstanceDto).collect(Collectors.toList());
    }

    // ── 9. List tasks ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OffboardingTaskDto> listTasks(Long instanceId) {
        return taskRepo.findByWorkflowInstanceId(instanceId).stream()
            .map(this::toTaskDto)
            .collect(Collectors.toList());
    }

    // ── 10. Add asset return ──────────────────────────────────────────────────

    public OffboardingAssetReturnDto addAssetReturn(Long instanceId, CreateAssetReturnDto dto) {
        findInstanceOrThrow(instanceId); // validate instance exists
        OffboardingAssetReturn asset = OffboardingAssetReturn.builder()
            .workflowInstanceId(instanceId)
            .taskId(dto.getTaskId())
            .assetDescription(dto.getAssetDescription())
            .assetType(dto.getAssetType() != null ? dto.getAssetType() : "IT")
            .expectedReturnDate(dto.getExpectedReturnDate())
            .isWrittenOff(false)
            .createdAt(OffsetDateTime.now())
            .build();
        asset = assetRepo.save(asset);
        return toAssetDto(asset);
    }

    // ── 11. Confirm asset return ──────────────────────────────────────────────

    public OffboardingAssetReturnDto confirmAssetReturn(Long assetId,
                                                         ConfirmAssetReturnDto dto,
                                                         Long confirmedBy) {
        OffboardingAssetReturn asset = assetRepo.findById(assetId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Retour d'actif introuvable: id=" + assetId));

        asset.setActualReturnDate(LocalDate.now());
        asset.setConditionOnReturn(dto.getConditionOnReturn());
        asset.setConfirmedBy(confirmedBy);
        asset.setConfirmedAt(OffsetDateTime.now());
        asset = assetRepo.save(asset);

        auditService.log(confirmedBy != null ? confirmedBy.toString() : "SYSTEM",
            "ASSET_RETURN_CONFIRMED", "OffboardingAssetReturn", assetId,
            null, "workflowInstanceId=" + asset.getWorkflowInstanceId());

        return toAssetDto(asset);
    }

    // ── 12. List asset returns ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OffboardingAssetReturnDto> listAssetReturns(Long instanceId) {
        return assetRepo.findByWorkflowInstanceId(instanceId).stream()
            .map(this::toAssetDto)
            .collect(Collectors.toList());
    }

    // ── 13. Get exit interview ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExitInterviewDto getExitInterview(Long instanceId) {
        return interviewRepo.findByWorkflowInstanceId(instanceId)
            .map(this::toInterviewDto)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Entretien de sortie introuvable pour le workflow id=" + instanceId));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private OffboardingWorkflowInstance findInstanceOrThrow(Long instanceId) {
        return instanceRepo.findById(instanceId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Workflow d'offboarding introuvable: id=" + instanceId));
    }

    private OffboardingTask findTaskOrThrow(Long taskId) {
        return taskRepo.findById(taskId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Tâche d'offboarding introuvable: id=" + taskId));
    }

    private String resolveContractType(Long contractId) {
        return jdbc.queryForObject(CONTRACT_TYPE_SQL, String.class, contractId);
    }

    private String resolveEmployeeName(Long profileId) {
        try {
            return jdbc.queryForObject(EMPLOYEE_NAME_SQL, String.class, profileId);
        } catch (Exception ex) {
            log.debug("Could not resolve name for profileId={}: {}", profileId, ex.getMessage());
            return null;
        }
    }

    /**
     * Seeds offboarding_asset_returns from the employee's IT provisioning record.
     * All assets in the provisioning record are included (provided or not) to ensure
     * every registered item is tracked for return.
     */
    private void seedItAssetReturns(OffboardingWorkflowInstance instance, Long profileId) {
        try {
            Long candidateId = profileService.getCandidateId(profileId);
            if (candidateId == null) {
                log.warn("seedItAssetReturns: no candidateId for profileId={}", profileId);
                return;
            }

            Optional<ItProvisioning> provOpt = itProvisioningRepo.findByCandidateId(candidateId);
            if (provOpt.isEmpty()) {
                log.info("seedItAssetReturns: no IT provisioning record for candidateId={}", candidateId);
                return;
            }

            ItProvisioning prov = provOpt.get();
            List<ItAsset> assets = itAssetRepo.findByProvisioningId(prov.getId());
            log.info("seedItAssetReturns: found {} asset(s) for provisioningId={}", assets.size(), prov.getId());

            if (assets.isEmpty()) return;

            LocalDate expectedReturn = instance.getLastWorkingDay() != null
                ? instance.getLastWorkingDay()
                : calculateDueDate(instance.getTriggerDate(), 3);

            List<OffboardingAssetReturn> returns = assets.stream()
                .map(a -> {
                    String label = a.getAssetType() != null
                        ? a.getAssetType().getLabelFr() : "Équipement IT";
                    String brandModel = a.getBrandModel() != null
                        ? a.getBrandModel().trim() : "";
                    String desc = brandModel.isEmpty()
                        ? label : brandModel + " (" + label + ")";
                    if (a.getSerialNumber() != null && !a.getSerialNumber().isBlank()) {
                        desc += " — S/N " + a.getSerialNumber().trim();
                    }
                    return OffboardingAssetReturn.builder()
                        .workflowInstanceId(instance.getId())
                        .assetDescription(desc)
                        .assetType("IT")
                        .expectedReturnDate(expectedReturn)
                        .isWrittenOff(false)
                        .createdAt(OffsetDateTime.now())
                        .build();
                })
                .collect(Collectors.toList());

            assetRepo.saveAll(returns);
            log.info("Seeded {} IT asset return(s) for offboarding instance {}", returns.size(), instance.getId());

        } catch (Exception ex) {
            log.error("Could not seed IT asset returns for profileId={}: {}", profileId, ex.getMessage(), ex);
        }
    }

    /**
     * Re-seeds IT asset returns for an existing workflow instance.
     * Skips assets already present to avoid duplicates.
     */
    @Transactional
    public List<OffboardingAssetReturnDto> reseedItAssets(Long instanceId) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        Long profileId = instance.getEmployeeProfileId();

        // Only add assets not already tracked
        Set<String> existing = assetRepo.findByWorkflowInstanceId(instanceId).stream()
            .map(OffboardingAssetReturn::getAssetDescription)
            .collect(Collectors.toSet());

        Long candidateId = profileService.getCandidateId(profileId);
        if (candidateId == null) return listAssetReturns(instanceId);

        Optional<ItProvisioning> provOpt = itProvisioningRepo.findByCandidateId(candidateId);
        if (provOpt.isEmpty()) return listAssetReturns(instanceId);

        List<ItAsset> assets = itAssetRepo.findByProvisioningId(provOpt.get().getId());
        LocalDate expectedReturn = instance.getLastWorkingDay() != null
            ? instance.getLastWorkingDay()
            : calculateDueDate(instance.getTriggerDate(), 3);

        List<OffboardingAssetReturn> toAdd = assets.stream()
            .map(a -> {
                String label = a.getAssetType() != null ? a.getAssetType().getLabelFr() : "Équipement IT";
                String brandModel = a.getBrandModel() != null ? a.getBrandModel().trim() : "";
                String desc = brandModel.isEmpty() ? label : brandModel + " (" + label + ")";
                if (a.getSerialNumber() != null && !a.getSerialNumber().isBlank()) {
                    desc += " — S/N " + a.getSerialNumber().trim();
                }
                return desc;
            })
            .filter(desc -> !existing.contains(desc))
            .map(desc -> OffboardingAssetReturn.builder()
                .workflowInstanceId(instanceId)
                .assetDescription(desc)
                .assetType("IT")
                .expectedReturnDate(expectedReturn)
                .isWrittenOff(false)
                .createdAt(OffsetDateTime.now())
                .build())
            .collect(Collectors.toList());

        if (!toAdd.isEmpty()) {
            assetRepo.saveAll(toAdd);
            log.info("Re-seeded {} IT asset return(s) for offboarding instance {}", toAdd.size(), instanceId);
        }

        return listAssetReturns(instanceId);
    }

    /**
     * Calculates a due date by adding slaWorkingDays working days (skipping Sat/Sun).
     */
    LocalDate calculateDueDate(LocalDate base, int slaWorkingDays) {
        LocalDate date = base;
        int added = 0;
        while (added < slaWorkingDays) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }

    private void notifyTaskOwners(Long paysId, String title, String message, String permission) {
        try {
            List<Map<String, Object>> users = jdbc.queryForList(
                USERS_WITH_PERM_SQL, permission, paysId);
            List<String> emails = new ArrayList<>();
            for (Map<String, Object> row : users) {
                Long uid   = ((Number) row.get("id")).longValue();
                String email = (String) row.get("email");
                try {
                    jdbc.update(INSERT_NOTIF_SQL, uid, "RH", title, message);
                } catch (Exception ex) {
                    log.error("Failed in-app notification for userId={}: {}", uid, ex.getMessage());
                }
                if (email != null && !email.isBlank()) {
                    emails.add(email);
                }
            }
            if (!emails.isEmpty()) {
                mailService.sendRoutedEmail(emails, List.of(), List.of(),
                    "[DAF360 RH] " + title, message);
            }
        } catch (Exception e) {
            log.error("Failed to notify task owners for permission={}: {}", permission, e.getMessage());
        }
    }

    // ── DTO mappers ───────────────────────────────────────────────────────────

    private OffboardingWorkflowInstanceDto toInstanceDto(OffboardingWorkflowInstance w) {
        List<OffboardingTaskDto> taskDtos = (w.getTasks() != null)
            ? w.getTasks().stream().map(this::toTaskDto).collect(Collectors.toList())
            : taskRepo.findByWorkflowInstanceId(w.getId()).stream()
                .map(this::toTaskDto).collect(Collectors.toList());

        String employeeFullName = resolveEmployeeName(w.getEmployeeProfileId());
        String handoverManagerName = (w.getHandoverManagerProfileId() != null)
            ? resolveEmployeeName(w.getHandoverManagerProfileId())
            : null;

        return OffboardingWorkflowInstanceDto.builder()
            .id(w.getId())
            .paysId(w.getPaysId())
            .employeeProfileId(w.getEmployeeProfileId())
            .employeeFullName(employeeFullName)
            .contractId(w.getContractId())
            .triggerDate(w.getTriggerDate())
            .lastWorkingDay(w.getLastWorkingDay())
            .departureReason(w.getDepartureReason())
            .departureNotes(w.getDepartureNotes())
            .status(w.getStatus())
            .initiatedBy(w.getInitiatedBy())
            .validatedBy(w.getValidatedBy())
            .validatedAt(w.getValidatedAt())
            .cancelledBy(w.getCancelledBy())
            .cancelledAt(w.getCancelledAt())
            .cancellationReason(w.getCancellationReason())
            .slaBreachFlag(w.getSlaBreachFlag())
            .completionDate(w.getCompletionDate())
            .createdAt(w.getCreatedAt())
            .updatedAt(w.getUpdatedAt())
            .handoverManagerProfileId(w.getHandoverManagerProfileId())
            .handoverManagerName(handoverManagerName)
            .tasks(taskDtos)
            .build();
    }

    private OffboardingTaskDto toTaskDto(OffboardingTask t) {
        return OffboardingTaskDto.builder()
            .id(t.getId())
            .workflowInstanceId(t.getWorkflowInstance() != null
                ? t.getWorkflowInstance().getId() : null)
            .taskCode(t.getTaskCode())
            .taskLabel(t.getTaskLabel())
            .ownerRole(t.getOwnerRole())
            .ownerUserId(t.getOwnerUserId())
            .isMandatory(t.getIsMandatory())
            .isBlocking(t.getIsBlocking())
            .dueDate(t.getDueDate())
            .status(t.getStatus())
            .completedBy(t.getCompletedBy())
            .completedAt(t.getCompletedAt())
            .skippedBy(t.getSkippedBy())
            .skipReason(t.getSkipReason())
            .comments(t.getComments())
            .attachedDocumentUrl(t.getAttachedDocumentUrl())
            .slaBreachDate(t.getSlaBreachDate())
            .createdAt(t.getCreatedAt())
            .build();
    }

    private ExitInterviewDto toInterviewDto(ExitInterview e) {
        return ExitInterviewDto.builder()
            .id(e.getId())
            .workflowInstanceId(e.getWorkflowInstance() != null
                ? e.getWorkflowInstance().getId() : null)
            .conductedBy(e.getConductedBy())
            .conductedDate(e.getConductedDate())
            .departureReasons(e.getDepartureReasons())
            .feedbackText(e.getFeedbackText())
            .isAnonymised(e.getIsAnonymised())
            .anonymisedAt(e.getAnonymisedAt())
            .visibleToRoles(e.getVisibleToRoles())
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .build();
    }

    private OffboardingAssetReturnDto toAssetDto(OffboardingAssetReturn a) {
        return OffboardingAssetReturnDto.builder()
            .id(a.getId())
            .workflowInstanceId(a.getWorkflowInstanceId())
            .taskId(a.getTaskId())
            .assetDescription(a.getAssetDescription())
            .assetType(a.getAssetType())
            .expectedReturnDate(a.getExpectedReturnDate())
            .actualReturnDate(a.getActualReturnDate())
            .conditionOnReturn(a.getConditionOnReturn())
            .confirmedBy(a.getConfirmedBy())
            .confirmedAt(a.getConfirmedAt())
            .isWrittenOff(a.getIsWrittenOff())
            .writeOffApprovedBy(a.getWriteOffApprovedBy())
            .writeOffReason(a.getWriteOffReason())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
