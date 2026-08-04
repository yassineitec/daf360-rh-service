package com.daf360.rh.service;

import com.daf360.rh.common.OffboardingStagePermissions;
import com.daf360.rh.common.PermissionCatalog;
import com.daf360.rh.domain.*;
import com.daf360.rh.dto.offboarding.*;
import com.daf360.rh.dto.profile.LifecycleTransitionDto;
import com.daf360.rh.dto.requests.GeneratedDocumentResponseDto;
import com.daf360.rh.domain.enums.LifecycleStatus;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    /**
     * The contract in force for a profile, newest first.
     *
     * Statuses come from `LifecycleStateMachine`: PERIODE_ESSAI, ACTIF and SUSPENDU are the
     * states where an employee is still under contract, plus the two CDD renewal states.
     * FIN_CONTRAT / RETRAITE / INACTIF are closed and must not decide the offboarding catalog.
     *
     * Ordered by start date so a renewed CDD resolves to the current row rather than the
     * original one; TOP 1 because we only need the type code.
     */
    private static final String ACTIVE_CONTRACT_SQL =
        "SELECT TOP 1 id FROM [dbo].[employee_contracts] " +
        "WHERE employee_profile_id = ? " +
        "  AND current_status_code IN ('PERIODE_ESSAI','ACTIF','SUSPENDU'," +
        "                              'RENOUVELLEMENT_CDD','CONVERSION_CDI') " +
        "ORDER BY date_debut DESC, id DESC";

    /**
     * Same LEFT JOIN reasoning as EMPLOYEE_IDENTITY_SQL below: an inner join through
     * candidates returned NULL for every profile whose candidate_id is NULL, so the
     * handover manager's name was blank for anyone not hired via recruitment.
     */
    private static final String EMPLOYEE_NAME_SQL =
        "SELECT COALESCE(" +
        "         NULLIF(LTRIM(RTRIM(CONCAT(c.first_name, ' ', c.last_name))), ''), " +
        "         NULLIF(LTRIM(RTRIM(u.fullName)), '')" +
        "       ) " +
        "FROM [dbo].[employee_profiles] ep " +
        "LEFT JOIN [dbo].[candidates] c ON c.id = ep.candidate_id " +
        "LEFT JOIN [dbo].[Users] u      ON u.id = ep.user_id " +
        "WHERE ep.id = ?";

    /**
     * Name + avatar inputs in one round-trip. Gender and photo_url come from the profile so
     * the offboarding board and the case page can show the same avatar as every other
     * employee view instead of falling back to initials for everyone.
     */
    /**
     * Name + avatar inputs in one round-trip.
     *
     * Driven FROM employee_profiles with LEFT JOINs, not through an inner join on
     * candidates: `employee_profiles.candidate_id` is NULL for anyone who was not hired
     * through the recruitment pipeline, and the inner join then matched no row at all —
     * discarding the `gender` and `photo_url` that sit on the profile itself and are
     * perfectly populated. That is exactly why every offboarding card fell back to
     * initials and showed no name.
     *
     * Name comes from the candidate when there is one, else from the portal user.
     */
    private static final String EMPLOYEE_IDENTITY_SQL =
        "SELECT COALESCE(" +
        "         NULLIF(LTRIM(RTRIM(CONCAT(c.first_name, ' ', c.last_name))), ''), " +
        "         NULLIF(LTRIM(RTRIM(u.fullName)), '')" +
        "       ) AS full_name, " +
        "       ep.gender, ep.photo_url " +
        "FROM [dbo].[employee_profiles] ep " +
        "LEFT JOIN [dbo].[candidates] c ON c.id = ep.candidate_id " +
        "LEFT JOIN [dbo].[Users] u      ON u.id = ep.user_id " +
        "WHERE ep.id = ?";

    /** Portal user → display name, for the stage-2 validation stamps. */
    private static final String USER_NAME_SQL =
        "SELECT NULLIF(LTRIM(RTRIM(u.fullName)), '') FROM [dbo].[Users] u WHERE u.id = ?";

    private static final List<String> ACTIVE_STATUSES =
        List.of("PENDING", "IN_PROGRESS", "BLOCKED");

    private static final Set<String> CHECKLIST_GROUPS = Set.of("HANDOVER", "ACCESS", "KIT");

    /**
     * The fixed checklist lines seeded on every new file: accounts to revoke (stage 4) and
     * the documents RH owes the employee (stage 5).
     *
     * HANDOVER is absent on purpose — each departure hands over different work, so that
     * list is built per file rather than copied from here. Kept in step with the same
     * VALUES block in V60's backfill.
     */
    private record ChecklistSeed(String group, String code, String label, int order) {}

    private static final List<ChecklistSeed> CHECKLIST_SEEDS = List.of(
        new ChecklistSeed("ACCESS", "WORKSPACE_CLOSE",    "Fermeture Workspace / Microsoft 365",    1),
        new ChecklistSeed("ACCESS", "VPN_ERP_REVOKE",     "Révocation des accès VPN / ERP",         2),
        new ChecklistSeed("ACCESS", "MAIL_REDIRECT",      "Redirection des emails vers le manager", 3),
        new ChecklistSeed("KIT",    "WORK_CERTIFICATE",   "Certificat de travail",                  1),
        new ChecklistSeed("KIT",    "END_OF_CONTRACT",    "Attestation de fin de contrat",          2),
        new ChecklistSeed("KIT",    "SETTLEMENT_RECEIPT", "Reçu pour solde de tout compte",         3)
    );

    private final OffboardingWorkflowInstanceRepository instanceRepo;
    private final OffboardingTaskRepository             taskRepo;
    private final OffboardingAssetReturnRepository      assetRepo;
    private final ExitInterviewRepository               interviewRepo;
    private final OffboardingTaskCatalogRepository      catalogRepo;
    private final OffboardingChecklistItemRepository    checklistRepo;
    private final OffboardingSettlementLineRepository   settlementRepo;
    private final AuditLogRepository                    auditLogRepository;
    private final EmployeeProfileService                profileService;
    /** Direct repo for the décharge, matching EmployeeRequestService's own pattern. */
    private final EmployeeProfileRepository            profileRepo;
    private final DocumentGenerationService            documentGenerationService;
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

        /*
         * Resolve the contract type for the catalog lookup.
         *
         * `contractId` is optional and NO caller sends it — not the list page's modal, not the
         * profile action — so this always fell through to the "CDI" default. Every file got the
         * CDI task list, and the CDD / STAGE / FREELANCE / CIVP rows V44 seeds per pays were
         * dead weight: a three-month intern was offboarded with a 15-working-day solde de tout
         * compte and a work certificate.
         *
         * So resolve it from the PROFILE when the caller does not supply one. Server-side is
         * also the right place for it: the contract in force is a fact about the employee, not
         * something a client should be trusted to state.
         */
        String contractType = "CDI";
        Long contractId = request.getContractId();
        if (contractId == null) {
            contractId = findActiveContractId(profileId);
        }
        if (contractId != null) {
            try {
                String resolved = resolveContractType(contractId);
                if (resolved != null && !resolved.isBlank()) contractType = resolved;
            } catch (Exception e) {
                log.warn("Could not resolve contract type for contractId={} — falling back to CDI: {}",
                    contractId, e.getMessage());
            }
        } else {
            log.warn("No contract found for profileId={} — offboarding will use the CDI catalog", profileId);
        }
        // Carry the resolved contract onto the instance, so the file records which one it closed.
        final Long resolvedContractId = contractId;

        // Profile → OFFBOARDING. Deliberately never fatal: a lifecycle that cannot move is
        // not a reason to refuse opening the departure file. Uses the same guarded helper as
        // validate / cancel / reopen so all four behave identically.
        // (Called before the instance exists, so it takes the profile id directly.)
        moveLifecycleForProfile(profileId, LifecycleStatus.OFFBOARDING,
            "Offboarding initié — " + request.getDepartureReason(), initiatedBy, null);

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
            .contractId(resolvedContractId)
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

        // Seed the fixed ACCESS and KIT checklists (V60)
        seedChecklistItems(instanceId);

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
        assertMayActOnTask(task);

        OffboardingWorkflowInstance instance = task.getWorkflowInstance();
        // A validated or cancelled file is history. The UI blocked this; the API did not,
        // so tasks on a closed dossier could still be completed by hand.
        if (instance != null && !ACTIVE_STATUSES.contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Le dossier est " + instance.getStatus() + " — ses tâches ne sont plus modifiables.");
        }
        if ("DONE".equals(task.getStatus()) || "SKIPPED".equals(task.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "La tâche est déjà dans le statut " + task.getStatus());
        }

        /*
         * The settlement cannot be validated while other blocking work is open.
         *
         * The UI has always disabled the button in that case, but the rule lived only there —
         * so the one task that releases the money was enforceable by anyone bypassing the
         * screen. `findBlockingIncomplete` includes FINAL_SETTLEMENT itself, hence the filter.
         */
        if ("FINAL_SETTLEMENT".equals(task.getTaskCode())) {
            List<OffboardingTask> others = taskRepo.findBlockingIncomplete(instance.getId())
                .stream().filter(t -> !t.getId().equals(taskId)).collect(Collectors.toList());
            if (!others.isEmpty()) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le solde de tout compte ne peut pas être validé : "
                    + others.size() + " tâche(s) bloquante(s) restent ouvertes.");
            }
        }

        task.setStatus("DONE");
        task.setCompletedBy(completedBy);
        task.setCompletedAt(OffsetDateTime.now());
        task.setComments(request.getComments());
        task.setAttachedDocumentUrl(request.getAttachedDocumentUrl());
        taskRepo.save(task);

        // Advance workflow status if it was BLOCKED
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

    // ── 2b. Update the declaration (stage 1) ──────────────────────────────────

    /**
     * Fills in stage 1 of the wizard.
     *
     * The file can be opened from a profile with nothing but a departure type, so the
     * declaration is completed here rather than at creation. Setting `lastWorkingDay` is
     * what marks the stage done and unlocks the downstream stages, so it carries a real
     * rule: it cannot precede the trigger date.
     *
     * Refused on a terminal file — a validated or cancelled departure is history.
     */
    public OffboardingWorkflowInstanceDto updateDeclaration(
            Long instanceId, UpdateDeclarationRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        if (!ACTIVE_STATUSES.contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "La déclaration d'un dossier " + instance.getStatus() + " ne peut plus être modifiée.");
        }

        if (request.getLastWorkingDay() != null
                && instance.getTriggerDate() != null
                && request.getLastWorkingDay().isBefore(instance.getTriggerDate())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Le dernier jour travaillé ne peut pas précéder la date de déclaration ("
                + instance.getTriggerDate() + ").");
        }

        LocalDate previousLastWorkingDay = instance.getLastWorkingDay();

        // Absent field == null == "leave alone". See the DTO's note on why this cannot
        // clear a value: the stage always posts its whole shape.
        if (request.getLastWorkingDay() != null)           instance.setLastWorkingDay(request.getLastWorkingDay());
        if (request.getTheoreticalExitDate() != null)      instance.setTheoreticalExitDate(request.getTheoreticalExitDate());
        if (request.getNoticePeriodLabel() != null)        instance.setNoticePeriodLabel(request.getNoticePeriodLabel());
        if (request.getNoticeWaiverRequested() != null)    instance.setNoticeWaiverRequested(request.getNoticeWaiverRequested());
        if (request.getJustificationDocumentUrl() != null) instance.setJustificationDocumentUrl(request.getJustificationDocumentUrl());
        if (request.getJustificationDocumentName() != null) instance.setJustificationDocumentName(request.getJustificationDocumentName());
        if (request.getDepartureNotes() != null)           instance.setDepartureNotes(request.getDepartureNotes());

        if (request.getHandoverManagerProfileId() != null) {
            instance.setHandoverManagerProfileId(request.getHandoverManagerProfileId());
            reassignHandoverTask(instanceId, request.getHandoverManagerProfileId());
        }

        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        // The seeded asset returns were dated from `triggerDate + 3 working days` because
        // no departure date existed yet. Now that one does, IT would otherwise be chasing
        // equipment against a deadline invented at creation.
        if (request.getLastWorkingDay() != null
                && !request.getLastWorkingDay().equals(previousLastWorkingDay)) {
            realignAssetReturnDates(instanceId, request.getLastWorkingDay());
        }

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_DECLARATION_UPDATED", "OffboardingWorkflowInstance", instanceId,
            String.valueOf(previousLastWorkingDay), "lastWorkingDay=" + instance.getLastWorkingDay());

        return toInstanceDto(instance);
    }

    /** Moves the KNOWLEDGE_TRANSFER task to the newly named manager, if it is still open. */
    private void reassignHandoverTask(Long instanceId, Long managerProfileId) {
        try {
            Long managerUserId = profileService.getUserId(managerProfileId);
            if (managerUserId == null) return;
            taskRepo.findByWorkflowInstanceId(instanceId).stream()
                .filter(t -> "KNOWLEDGE_TRANSFER".equals(t.getTaskCode())
                          && !"DONE".equals(t.getStatus())
                          && !"SKIPPED".equals(t.getStatus()))
                .findFirst()
                .ifPresent(t -> {
                    t.setOwnerUserId(managerUserId);
                    taskRepo.save(t);
                });
        } catch (Exception ex) {
            log.warn("Could not reassign KNOWLEDGE_TRANSFER for instance {}: {}", instanceId, ex.getMessage());
        }
    }

    /**
     * Seeds the fixed ACCESS and KIT lines. Guarded per line so a re-run (or a file whose
     * seeding half-failed) tops up rather than duplicating — the unique constraint on
     * (instance, group, code) would otherwise abort the whole `startOffboarding`.
     *
     * Never throws: a file without its checklists is recoverable (the lines can be added by
     * hand); a departure that could not be started is not.
     */
    private void seedChecklistItems(Long instanceId) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<OffboardingChecklistItem> toAdd = CHECKLIST_SEEDS.stream()
                .filter(s -> !checklistRepo.existsByWorkflowInstanceIdAndGroupCodeAndItemCode(
                    instanceId, s.group(), s.code()))
                .map(s -> OffboardingChecklistItem.builder()
                    .workflowInstanceId(instanceId)
                    .groupCode(s.group())
                    .itemCode(s.code())
                    .itemLabel(s.label())
                    .isDone(false)
                    .orderIndex(s.order())
                    .createdAt(now)
                    .build())
                .collect(Collectors.toList());

            if (!toAdd.isEmpty()) {
                checklistRepo.saveAll(toAdd);
                log.info("Seeded {} checklist item(s) for offboarding instance {}",
                    toAdd.size(), instanceId);
            }
        } catch (Exception ex) {
            log.error("Could not seed checklist items for instance {}: {}",
                instanceId, ex.getMessage(), ex);
        }
    }

    /** Re-dates only the returns nobody has confirmed yet; a settled return is history. */
    private void realignAssetReturnDates(Long instanceId, LocalDate lastWorkingDay) {
        List<OffboardingAssetReturn> pending = assetRepo.findByWorkflowInstanceId(instanceId).stream()
            .filter(a -> a.getActualReturnDate() == null)
            .peek(a -> a.setExpectedReturnDate(lastWorkingDay))
            .collect(Collectors.toList());
        if (!pending.isEmpty()) {
            assetRepo.saveAll(pending);
            log.info("Re-dated {} pending asset return(s) of instance {} to {}",
                pending.size(), instanceId, lastWorkingDay);
        }
    }

    // ── 2d. Stage 3 — Passation ───────────────────────────────────────────────

    /**
     * Names the successor and records the PV de passation.
     *
     * Naming the successor used to be possible ONLY in the optional search on the "Démarrer
     * un offboarding" modal — so a file opened from a profile page had no handover manager,
     * no screen could give it one, `KNOWLEDGE_TRANSFER` had no owner, and stage 2's manager
     * panel had nobody but RH able to stamp it. This is the screen that fixes that.
     */
    public OffboardingWorkflowInstanceDto updateHandover(
            Long instanceId, UpdateHandoverRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

        if (request.getHandoverManagerProfileId() != null) {
            if (request.getHandoverManagerProfileId().equals(instance.getEmployeeProfileId())) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le successeur ne peut pas être la personne qui quitte l'entreprise.");
            }
            instance.setHandoverManagerProfileId(request.getHandoverManagerProfileId());
            reassignHandoverTask(instanceId, request.getHandoverManagerProfileId());
        }
        if (request.getHandoverMinutesUrl() != null) {
            instance.setHandoverMinutesUrl(request.getHandoverMinutesUrl());
        }
        if (request.getHandoverMinutesName() != null) {
            instance.setHandoverMinutesName(request.getHandoverMinutesName());
        }

        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_HANDOVER_UPDATED", "OffboardingWorkflowInstance", instanceId,
            null, "managerProfileId=" + instance.getHandoverManagerProfileId()
                + " pv=" + (instance.getHandoverMinutesUrl() != null));

        return toInstanceDto(instance);
    }

    // ── 2f. Stage 4 — Informatique & Matériel ─────────────────────────────────

    public OffboardingWorkflowInstanceDto updateItSecurity(
            Long instanceId, UpdateItSecurityRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

        if (request.getAccountDeactivationAt() != null) {
            instance.setAccountDeactivationAt(request.getAccountDeactivationAt());
        }
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_IT_SECURITY_UPDATED", "OffboardingWorkflowInstance", instanceId,
            null, "accountDeactivationAt=" + instance.getAccountDeactivationAt());

        return toInstanceDto(instance);
    }

    /**
     * The décharge de matériel — what the employee signs to certify the returns.
     *
     * Lists the tracked assets with their state, because a décharge that does not enumerate
     * what was handed back certifies nothing. Written-off items are named as such rather than
     * omitted: their absence is exactly what a signature has to acknowledge.
     *
     * Generated from live data every time, so re-generating after a late return produces a
     * correct document rather than a stale one. The previous URL is simply replaced — the
     * `generated_documents` row survives with its own verification code, so a copy already
     * handed to the employee stays verifiable.
     */
    public OffboardingWorkflowInstanceDto generateDischarge(Long instanceId, Long actorId) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

        List<OffboardingAssetReturn> assets = assetRepo.findByWorkflowInstanceId(instanceId);
        if (assets.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Aucun équipement n'est suivi pour ce dossier — la décharge n'aurait rien à attester.");
        }

        EmployeeProfile profile = profileRepo.findById(instance.getEmployeeProfileId())
            .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                "Profil introuvable: id=" + instance.getEmployeeProfileId()));

        GeneratedDocumentResponseDto generated = documentGenerationService.generateStandalone(
            "offboarding-" + instanceId,
            "OFFBOARDING_DISCHARGE",
            DISCHARGE_TEMPLATE,
            profile,
            Map.of(
                "{{assets}}",         renderAssetLines(assets),
                "{{lastWorkingDay}}", instance.getLastWorkingDay() != null
                                      ? instance.getLastWorkingDay().toString() : "—"
            ),
            actorId);

        instance.setDischargeDocumentUrl(generated.getFileUrl());
        instance.setDischargeDocumentName("Décharge de matériel.pdf");
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_DISCHARGE_GENERATED", "OffboardingWorkflowInstance", instanceId,
            null, "assets=" + assets.size() + " file=" + generated.getFileUrl());

        return toInstanceDto(instance);
    }

    private static final String DISCHARGE_TEMPLATE = """
            DÉCHARGE DE MATÉRIEL — {{entity}}
            =====================================

            Je soussigné(e) {{fullName}}, dont le dernier jour travaillé est
            fixé au {{lastWorkingDay}}, reconnais l'état suivant du matériel
            mis à ma disposition :

            {{assets}}

            Je certifie l'exactitude des informations ci-dessus.

            Fait le {{date}}.

            Signature de l'intéressé(e)          Pour la Direction
            """;

    /** One line per asset: what it is, its serial, and whether it is actually back. */
    private String renderAssetLines(List<OffboardingAssetReturn> assets) {
        return assets.stream().map(a -> {
            StringBuilder line = new StringBuilder("  - ").append(a.getAssetDescription());
            if (a.getSerialNumber() != null && !a.getSerialNumber().isBlank()) {
                line.append(" (S/N ").append(a.getSerialNumber().trim()).append(")");
            }
            if (Boolean.TRUE.equals(a.getIsWrittenOff())) {
                line.append(" : NON RESTITUÉ — perte actée");
            } else if (a.getActualReturnDate() != null) {
                line.append(" : restitué le ").append(a.getActualReturnDate());
                if (a.getConditionOnReturn() != null && !a.getConditionOnReturn().isBlank()) {
                    line.append(" (état : ").append(a.getConditionOnReturn()).append(")");
                }
            } else {
                line.append(" : NON RESTITUÉ à ce jour");
            }
            return line.toString();
        }).collect(Collectors.joining("\n"));
    }

    // ── 2i. Audit trail ───────────────────────────────────────────────────────

    /**
     * The complete history of one file, newest first.
     *
     * Gathers across the six entity types a file touches, because `entityId` is only unique
     * within a type. Reads the audit log itself rather than inferring events from surviving
     * fields, which is why skipped tasks, deleted checklist lines and corrected settlement
     * amounts appear here at all — none of them leave a trace on the instance.
     */
    @Transactional(readOnly = true)
    public List<OffboardingAuditEntryDto> getAuditTrail(Long instanceId) {
        findInstanceOrThrow(instanceId);

        Map<String, List<String>> idsByType = new java.util.LinkedHashMap<>();
        idsByType.put("OffboardingWorkflowInstance", List.of(String.valueOf(instanceId)));
        idsByType.put("OffboardingTask", idsOf(
            taskRepo.findByWorkflowInstanceId(instanceId).stream().map(OffboardingTask::getId)));
        idsByType.put("OffboardingAssetReturn", idsOf(
            assetRepo.findByWorkflowInstanceId(instanceId).stream()
                .map(OffboardingAssetReturn::getId)));
        idsByType.put("OffboardingChecklistItem", idsOf(
            checklistRepo.findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(instanceId)
                .stream().map(OffboardingChecklistItem::getId)));
        idsByType.put("OffboardingSettlementLine", idsOf(
            settlementRepo.findByWorkflowInstanceIdOrderByOrderIndexAsc(instanceId).stream()
                .map(OffboardingSettlementLine::getId)));
        idsByType.put("ExitInterview", idsOf(
            interviewRepo.findByWorkflowInstanceId(instanceId).stream().map(ExitInterview::getId)));

        // One name lookup per distinct actor, not per row: a busy file has many entries and
        // only a few people.
        Map<String, String> actorNames = new java.util.HashMap<>();

        return idsByType.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .flatMap(e -> auditLogRepository
                .findByEntityTypeAndEntityIdInOrderByTimestampDesc(e.getKey(), e.getValue())
                .stream())
            .sorted(java.util.Comparator.comparing(
                AuditLog::getTimestamp,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .map(a -> OffboardingAuditEntryDto.builder()
                .timestamp(a.getTimestamp())
                .action(a.getAction())
                .entityType(a.getEntityType())
                .entityId(a.getEntityId())
                .actorName(actorNames.computeIfAbsent(
                    a.getUserId() == null ? "SYSTEM" : a.getUserId(), this::displayActor))
                .oldValue(a.getOldValue())
                .newValue(a.getNewValue())
                .build())
            .collect(Collectors.toList());
    }

    private List<String> idsOf(java.util.stream.Stream<Long> ids) {
        return ids.filter(java.util.Objects::nonNull).map(String::valueOf)
            .collect(Collectors.toList());
    }

    /** `userId` is stored as a string and may be "SYSTEM" or an id whose user no longer exists. */
    private String displayActor(String userId) {
        if (userId == null || "SYSTEM".equals(userId)) return "Système";
        try {
            String name = resolveUserName(Long.valueOf(userId));
            return name != null ? name : userId;
        } catch (NumberFormatException e) {
            return userId;
        }
    }

    /** The same trail as CSV, for the drawer's "Télécharger le log complet". */
    @Transactional(readOnly = true)
    public String getAuditTrailCsv(Long instanceId) {
        StringBuilder csv = new StringBuilder("Date;Action;Entité;Acteur;Avant;Après\n");
        for (OffboardingAuditEntryDto e : getAuditTrail(instanceId)) {
            csv.append(csvCell(e.getTimestamp() != null ? e.getTimestamp().toString() : ""))
               .append(';').append(csvCell(e.getAction()))
               .append(';').append(csvCell(e.getEntityType() + "#" + e.getEntityId()))
               .append(';').append(csvCell(e.getActorName()))
               .append(';').append(csvCell(e.getOldValue()))
               .append(';').append(csvCell(e.getNewValue()))
               .append('\n');
        }
        return csv.toString();
    }

    /**
     * Quotes for Excel's semicolon dialect. Also strips a leading =, +, - or @ by prefixing a
     * quote: an audit value is attacker-influenced text (skip reasons, comments) and Excel
     * executes those as formulas.
     */
    private String csvCell(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) {
            v = "'" + v;
        }
        return "\"" + v + "\"";
    }

    // ── 2h. Stage 6 — Solde de tout compte ────────────────────────────────────

    public OffboardingWorkflowInstanceDto updateSettlement(
            Long instanceId, UpdateSettlementRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertMayEditSettlement();

        if (request.getSettlementExecutionDate() != null) {
            instance.setSettlementExecutionDate(request.getSettlementExecutionDate());
        }
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_SETTLEMENT_UPDATED", "OffboardingWorkflowInstance", instanceId,
            null, "executionDate=" + instance.getSettlementExecutionDate());

        return toInstanceDto(instance);
    }

    public OffboardingSettlementDto addSettlementLine(
            Long instanceId, SaveSettlementLineDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertMayEditSettlement();

        int nextOrder = settlementRepo.findByWorkflowInstanceIdOrderByOrderIndexAsc(instanceId)
            .stream().mapToInt(l -> l.getOrderIndex() == null ? 0 : l.getOrderIndex())
            .max().orElse(0) + 1;

        settlementRepo.save(OffboardingSettlementLine.builder()
            .workflowInstanceId(instanceId)
            .label(request.getLabel().trim())
            .amount(request.getAmount())
            .isSuggested(false)
            .orderIndex(nextOrder)
            .createdBy(actorId)
            .createdAt(OffsetDateTime.now())
            .build());

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_SETTLEMENT_LINE_ADDED", "OffboardingWorkflowInstance", instanceId,
            null, request.getLabel() + "=" + request.getAmount());

        return buildSettlement(instanceId);
    }

    /** Editing clears `isSuggested`: once a human touches a figure it is theirs, not ours. */
    public OffboardingSettlementDto updateSettlementLine(
            Long lineId, SaveSettlementLineDto request, Long actorId) {

        OffboardingSettlementLine line = settlementRepo.findById(lineId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Ligne de solde introuvable: id=" + lineId));

        assertActive(findInstanceOrThrow(line.getWorkflowInstanceId()));
        assertMayEditSettlement();

        String before = line.getLabel() + "=" + line.getAmount();
        line.setLabel(request.getLabel().trim());
        line.setAmount(request.getAmount());
        line.setIsSuggested(false);
        line.setUpdatedAt(OffsetDateTime.now());
        settlementRepo.save(line);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_SETTLEMENT_LINE_UPDATED", "OffboardingSettlementLine", lineId,
            before, line.getLabel() + "=" + line.getAmount());

        return buildSettlement(line.getWorkflowInstanceId());
    }

    public OffboardingSettlementDto deleteSettlementLine(Long lineId, Long actorId) {
        OffboardingSettlementLine line = settlementRepo.findById(lineId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Ligne de solde introuvable: id=" + lineId));

        Long instanceId = line.getWorkflowInstanceId();
        assertActive(findInstanceOrThrow(instanceId));
        assertMayEditSettlement();

        settlementRepo.delete(line);
        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_SETTLEMENT_LINE_DELETED", "OffboardingSettlementLine", lineId,
            line.getLabel() + "=" + line.getAmount(), null);

        return buildSettlement(instanceId);
    }

    /**
     * Seeds the breakdown with what CAN be derived, so RH starts from something.
     *
     * Only the prorata 13ᵉ mois is computable here: net salary × months worked this year ÷ 12,
     * from `salaire_net_rh` (V24) and the departure date. The other two standard lines are
     * added as empty placeholders rather than guessed — congés payés needs a leave balance
     * (no such table) and l'indemnité de rupture needs a per-pays convention scale (exists
     * nowhere). A zero someone has to fill in is honest; an invented amount is not.
     *
     * Refuses to run twice: it is a starting point, not a recalculation, and re-seeding would
     * silently duplicate lines RH had already adjusted.
     */
    public OffboardingSettlementDto suggestSettlement(Long instanceId, Long actorId) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertMayEditSettlement();

        if (settlementRepo.existsByWorkflowInstanceId(instanceId)) {
            throw new AppException(ErrorCode.ALREADY_EXISTS,
                "Le solde comporte déjà des lignes — ajoutez ou modifiez-les directement.");
        }

        EmployeeProfile profile = loadProfile(instance);
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate reference = instance.getLastWorkingDay() != null
            ? instance.getLastWorkingDay() : LocalDate.now();

        List<OffboardingSettlementLine> seeded = new ArrayList<>();

        BigDecimal thirteenth = computeThirteenthMonthProrata(profile, reference);
        seeded.add(OffboardingSettlementLine.builder()
            .workflowInstanceId(instanceId).label("Prorata 13ᵉ mois")
            .amount(thirteenth).isSuggested(thirteenth.signum() > 0)
            .orderIndex(1).createdBy(actorId).createdAt(now).build());

        seeded.add(OffboardingSettlementLine.builder()
            .workflowInstanceId(instanceId).label("Congés payés non pris")
            .amount(BigDecimal.ZERO).isSuggested(false)
            .orderIndex(2).createdBy(actorId).createdAt(now).build());

        seeded.add(OffboardingSettlementLine.builder()
            .workflowInstanceId(instanceId).label("Indemnité de rupture")
            .amount(BigDecimal.ZERO).isSuggested(false)
            .orderIndex(3).createdBy(actorId).createdAt(now).build());

        settlementRepo.saveAll(seeded);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_SETTLEMENT_SUGGESTED", "OffboardingWorkflowInstance", instanceId,
            null, "prorata13=" + thirteenth);

        return buildSettlement(instanceId);
    }

    /**
     * Net salary × months worked in the departure year ÷ 12.
     *
     * Counts the departure month as worked (an employee leaving on the 15th worked that
     * month), and starts from the hire date when the employee joined during the same year.
     * Zero when no salary is on file — the caller then leaves the line unflagged so it reads
     * as "to fill in" rather than "computed as nothing".
     */
    private BigDecimal computeThirteenthMonthProrata(EmployeeProfile profile, LocalDate reference) {
        BigDecimal salary = profile.getSalaireNetRh() != null
            ? profile.getSalaireNetRh() : profile.getSalaireNetCandidat();
        if (salary == null || salary.signum() <= 0) return BigDecimal.ZERO;

        int firstMonth = 1;
        if (profile.getHireDate() != null
                && profile.getHireDate().getYear() == reference.getYear()) {
            firstMonth = profile.getHireDate().getMonthValue();
        }
        int monthsWorked = reference.getMonthValue() - firstMonth + 1;
        if (monthsWorked <= 0) return BigDecimal.ZERO;

        return salary
            .multiply(BigDecimal.valueOf(monthsWorked))
            .divide(BigDecimal.valueOf(12), 3, java.math.RoundingMode.HALF_UP);
    }

    private OffboardingSettlementDto buildSettlement(Long instanceId) {
        List<OffboardingSettlementLine> lines =
            settlementRepo.findByWorkflowInstanceIdOrderByOrderIndexAsc(instanceId);
        return OffboardingSettlementDto.builder()
            .lines(lines.stream()
                .map(l -> OffboardingSettlementDto.Line.builder()
                    .id(l.getId()).label(l.getLabel()).amount(l.getAmount())
                    .isSuggested(l.getIsSuggested()).orderIndex(l.getOrderIndex()).build())
                .collect(Collectors.toList()))
            .totalNet(settlementTotal(lines))
            .currency("TND")
            .build();
    }

    private BigDecimal settlementTotal(List<OffboardingSettlementLine> lines) {
        return lines.stream()
            .map(l -> l.getAmount() == null ? BigDecimal.ZERO : l.getAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertMayEditSettlement() {
        if (hasAuthority(PermissionCatalog.RH_OFFBOARDING_STAGE_PAYROLL)
            || hasAuthority(PermissionCatalog.RH_MANAGE_OFFBOARDING)) return;
        throw new AppException(ErrorCode.FORBIDDEN,
            "Le solde de tout compte est réservé au service financier.");
    }

    /**
     * "Virement — RIB •••• 4821" from the profile's bank details, so stage 6 can state how the
     * money leaves without stage 6 storing bank data of its own. Masked: the panel needs to
     * identify the account, not reproduce it.
     */
    private String resolveSettlementPaymentMode(Long profileId) {
        try {
            EmployeeProfile p = profileRepo.findById(profileId).orElse(null);
            if (p == null) return null;
            String account = firstNonBlank(p.getRib(), p.getIban(), p.getBankAccountNumber());
            if (account == null) return null;
            String masked = account.length() <= 4
                ? account
                : "•••• " + account.substring(account.length() - 4);
            String bank = p.getBank() != null ? p.getBank().getLabelFr() : null;
            return bank != null ? "Virement — " + bank + " " + masked : "Virement — " + masked;
        } catch (Exception ex) {
            log.debug("Could not resolve the payment mode for profileId={}: {}",
                profileId, ex.getMessage());
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    // ── 2g. Stage 5 — Kit RH documents ────────────────────────────────────────

    /**
     * The three documents RH owes a departing employee, keyed by the KIT checklist codes so
     * generating one ticks its own line — the checklist and the documents are the same fact.
     *
     * Text lives here rather than in `document_templates` because these are per-departure and
     * short; the admin-managed templates exist for the request workflow's catalogue. Swap in a
     * template row later without touching callers: `generateStandalone` takes the body as a
     * string either way.
     */
    private static final Map<String, String> KIT_TEMPLATES = Map.of(
        "WORK_CERTIFICATE", """
            CERTIFICAT DE TRAVAIL — {{entity}}
            =====================================

            Nous soussignés certifions que {{fullName}} a été employé(e)
            au sein de notre entreprise du {{hireDate}} au {{lastWorkingDay}},
            en qualité de {{position}}.

            L'intéressé(e) est libre de tout engagement envers notre société.

            Fait le {{date}}.

            La Direction des Ressources Humaines
            """,
        "END_OF_CONTRACT", """
            ATTESTATION DE FIN DE CONTRAT — {{entity}}
            =====================================

            Nous attestons que le contrat de travail de {{fullName}},
            occupant le poste de {{position}}, a pris fin le {{lastWorkingDay}}
            pour le motif suivant : {{departureReason}}.

            La présente attestation est délivrée pour servir et valoir ce que de droit.

            Fait le {{date}}.

            La Direction des Ressources Humaines
            """,
        "SETTLEMENT_RECEIPT", """
            REÇU POUR SOLDE DE TOUT COMPTE — {{entity}}
            =====================================

            Je soussigné(e) {{fullName}}, dont le contrat a pris fin
            le {{lastWorkingDay}}, reconnais avoir reçu les sommes suivantes :

            {{settlementLines}}

            TOTAL NET : {{settlementTotal}}

            Ce reçu est délivré sous réserve des dispositions légales
            relatives au délai de contestation.

            Fait le {{date}}.

            Signature de l'intéressé(e)
            """
    );

    /**
     * Generates one Kit RH document and ticks its checklist line.
     *
     * The KIT group is seeded by V60, so the line always exists; generating attaches the file
     * to it rather than creating a parallel record of "documents produced". One list, one truth.
     */
    public OffboardingChecklistItemDto generateKitDocument(
            Long instanceId, String itemCode, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertMayEditChecklistGroup("KIT");

        String template = KIT_TEMPLATES.get(itemCode);
        if (template == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Document de Kit RH inconnu: " + itemCode
                + " (attendu: " + String.join(", ", KIT_TEMPLATES.keySet()) + ")");
        }

        OffboardingChecklistItem item = checklistRepo
            .findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(instanceId).stream()
            .filter(i -> "KIT".equals(i.getGroupCode()) && itemCode.equals(i.getItemCode()))
            .findFirst()
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "La ligne de Kit RH " + itemCode + " est absente de ce dossier."));

        EmployeeProfile profile = loadProfile(instance);
        GeneratedDocumentResponseDto generated = documentGenerationService.generateStandalone(
            "offboarding-" + instanceId,
            "OFFBOARDING_" + itemCode,
            template,
            profile,
            kitVariables(instance),
            actorId);

        OffsetDateTime now = OffsetDateTime.now();
        item.setDocumentUrl(generated.getFileUrl());
        item.setIsDone(true);
        item.setCompletedBy(actorId);
        item.setCompletedAt(now);
        checklistRepo.save(item);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_KIT_DOCUMENT_GENERATED", "OffboardingChecklistItem", item.getId(),
            null, "code=" + itemCode + " file=" + generated.getFileUrl());

        return toChecklistDto(item);
    }

    /** Every generated Kit RH document, zipped. Empty is refused rather than shipping 0 bytes. */
    @Transactional(readOnly = true)
    public byte[] buildKitArchive(Long instanceId) {
        findInstanceOrThrow(instanceId);

        List<OffboardingChecklistItem> ready = checklistRepo
            .findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(instanceId).stream()
            .filter(i -> "KIT".equals(i.getGroupCode())
                      && i.getDocumentUrl() != null && !i.getDocumentUrl().isBlank())
            .collect(Collectors.toList());

        if (ready.isEmpty()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Aucun document du Kit RH n'a encore été généré.");
        }

        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {

            for (OffboardingChecklistItem item : ready) {
                java.nio.file.Path path = java.nio.file.Paths.get(item.getDocumentUrl());
                if (!java.nio.file.Files.exists(path)) {
                    // A missing file must not sink the whole download — the others are still
                    // wanted, and the gap is visible in the archive's contents.
                    log.warn("Kit RH: file missing on disk for item {} ({})",
                        item.getId(), item.getDocumentUrl());
                    continue;
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(item.getItemLabel() + ".pdf"));
                java.nio.file.Files.copy(path, zip);
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new AppException(ErrorCode.DOCUMENT_GENERATION_FAILED,
                "Impossible de construire l'archive du Kit RH: " + ex.getMessage());
        }
    }

    /** Shared placeholders for the Kit RH bodies, including the settlement breakdown. */
    private Map<String, String> kitVariables(OffboardingWorkflowInstance instance) {
        List<OffboardingSettlementLine> lines =
            settlementRepo.findByWorkflowInstanceIdOrderByOrderIndexAsc(instance.getId());

        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("{{lastWorkingDay}}", instance.getLastWorkingDay() != null
            ? instance.getLastWorkingDay().toString() : "—");
        vars.put("{{departureReason}}", instance.getDepartureReason());
        vars.put("{{settlementLines}}", lines.isEmpty()
            ? "  (aucune ligne enregistrée)"
            : lines.stream()
                .map(l -> "  - " + l.getLabel() + " : " + l.getAmount() + " TND")
                .collect(Collectors.joining("\n")));
        vars.put("{{settlementTotal}}", settlementTotal(lines) + " TND");
        return vars;
    }

    private EmployeeProfile loadProfile(OffboardingWorkflowInstance instance) {
        return profileRepo.findById(instance.getEmployeeProfileId())
            .orElseThrow(() -> new AppException(ErrorCode.EMPLOYEE_NOT_FOUND,
                "Profil introuvable: id=" + instance.getEmployeeProfileId()));
    }

    // ── 2e. Checklists (stages 3, 4, 5) ───────────────────────────────────────

    /** Ticks or unticks one line. Permission follows the group, i.e. the owning stage. */
    public OffboardingChecklistItemDto updateChecklistItem(
            Long itemId, UpdateChecklistItemDto request, Long actorId) {

        OffboardingChecklistItem item = checklistRepo.findById(itemId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Élément de checklist introuvable: id=" + itemId));

        assertActive(findInstanceOrThrow(item.getWorkflowInstanceId()));
        assertMayEditChecklistGroup(item.getGroupCode());

        if (request.getIsDone() != null) {
            item.setIsDone(request.getIsDone());
            // Unticking clears the stamp: leaving "fait par X le 12/09" on an unticked line
            // is a record of something that is no longer claimed to have happened.
            item.setCompletedBy(Boolean.TRUE.equals(request.getIsDone()) ? actorId : null);
            item.setCompletedAt(Boolean.TRUE.equals(request.getIsDone()) ? OffsetDateTime.now() : null);
        }
        if (request.getDocumentUrl() != null) {
            item.setDocumentUrl(request.getDocumentUrl());
        }
        checklistRepo.save(item);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_CHECKLIST_UPDATED", "OffboardingChecklistItem", itemId,
            null, "group=" + item.getGroupCode() + " code=" + item.getItemCode()
                + " isDone=" + item.getIsDone());

        return toChecklistDto(item);
    }

    /**
     * Adds a line. HANDOVER above all: each departure hands over different work, so that
     * list cannot come from a catalog the way ACCESS and KIT do.
     */
    public OffboardingChecklistItemDto addChecklistItem(
            Long instanceId, CreateChecklistItemDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

        String group = request.getGroup().toUpperCase();
        if (!CHECKLIST_GROUPS.contains(group)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Groupe de checklist inconnu: " + group);
        }
        assertMayEditChecklistGroup(group);

        OffboardingChecklistItem item = checklistRepo.save(OffboardingChecklistItem.builder()
            .workflowInstanceId(instanceId)
            .groupCode(group)
            .itemCode(generateItemCode(instanceId, group))
            .itemLabel(request.getLabel().trim())
            .isDone(false)
            .orderIndex(nextOrderIndex(instanceId, group))
            .createdAt(OffsetDateTime.now())
            .build());

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_CHECKLIST_ADDED", "OffboardingChecklistItem", item.getId(),
            null, "group=" + group + " label=" + item.getItemLabel());

        return toChecklistDto(item);
    }

    /** Removes a line someone added by mistake. */
    public void deleteChecklistItem(Long itemId, Long actorId) {
        OffboardingChecklistItem item = checklistRepo.findById(itemId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Élément de checklist introuvable: id=" + itemId));

        assertActive(findInstanceOrThrow(item.getWorkflowInstanceId()));
        assertMayEditChecklistGroup(item.getGroupCode());

        checklistRepo.delete(item);
        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_CHECKLIST_DELETED", "OffboardingChecklistItem", itemId,
            item.getItemLabel(), "group=" + item.getGroupCode());
    }

    private void assertMayEditChecklistGroup(String groupCode) {
        String required = OffboardingStagePermissions.forChecklistGroup(groupCode);
        if (hasAuthority(required) || hasAuthority(PermissionCatalog.RH_MANAGE_OFFBOARDING)) return;
        throw new AppException(ErrorCode.FORBIDDEN,
            "Cette checklist est réservée à son service (permission requise : " + required + ").");
    }

    /** `HANDOVER_1755...` — unique per instance+group, which is all UX_checklist_item asks. */
    private String generateItemCode(Long instanceId, String group) {
        return group + "_" + System.currentTimeMillis();
    }

    private int nextOrderIndex(Long instanceId, String group) {
        return checklistRepo.findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(instanceId)
            .stream()
            .filter(i -> group.equals(i.getGroupCode()))
            .mapToInt(i -> i.getOrderIndex() == null ? 0 : i.getOrderIndex())
            .max().orElse(0) + 1;
    }

    // ── 2c. Stage 2 — Validation Manager & RH ─────────────────────────────────

    /**
     * The manager acknowledges the departure and comments.
     *
     * Who may do this cannot be expressed in `@PreAuthorize`: it is the *named* handover
     * manager of THIS file, which is only knowable after loading the row. RH passes too —
     * not as a shortcut, but because a file whose handover manager is unset (or who has no
     * portal account, or who has left) would otherwise have nobody able to validate it and
     * would deadlock at stage 2. The stamp records WHO validated, so an RH-recorded
     * approval is visibly attributed to RH rather than passing as the manager's own.
     */
    public OffboardingWorkflowInstanceDto validateAsManager(
            Long instanceId, ManagerValidationRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertDeclarationComplete(instance);

        Long managerUserId = resolveHandoverManagerUserId(instance.getHandoverManagerProfileId());
        boolean isNamedManager = managerUserId != null && managerUserId.equals(actorId);
        if (!isNamedManager && !hasAuthority(PermissionCatalog.RH_MANAGE_OFFBOARDING)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                "Seul le manager de rattachement du dossier (ou le service RH) peut donner cet avis.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        instance.setManagerValidatedBy(actorId);
        instance.setManagerValidatedAt(now);
        instance.setManagerComment(request.getComment());
        instance.setUpdatedAt(now);
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_MANAGER_VALIDATED", "OffboardingWorkflowInstance", instanceId,
            null, "byNamedManager=" + isNamedManager);

        notifyTaskOwners(instance.getPaysId(),
            "Avis manager enregistré — offboarding",
            "L'avis du manager a été enregistré pour le dossier d'offboarding id=" + instanceId
                + ". La validation RH peut être effectuée.",
            PermissionCatalog.RH_VALIDATE_OFFBOARDING);

        return toInstanceDto(instance);
    }

    /**
     * RH validates the departure and, if needed, adjusts the final date.
     *
     * Ordered after the manager: stage 2 is a two-step gate, and validating before anyone
     * has asked the manager would make the left panel permanently decorative.
     */
    public OffboardingWorkflowInstanceDto validateAsHr(
            Long instanceId, HrValidationRequestDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);
        assertDeclarationComplete(instance);

        if (instance.getManagerValidatedAt() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "L'avis du manager doit être enregistré avant la validation RH.");
        }

        LocalDate previousLastWorkingDay = instance.getLastWorkingDay();
        if (request.getLastWorkingDay() != null) {
            if (instance.getTriggerDate() != null
                    && request.getLastWorkingDay().isBefore(instance.getTriggerDate())) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Le dernier jour travaillé ne peut pas précéder la date de déclaration ("
                    + instance.getTriggerDate() + ").");
            }
            instance.setLastWorkingDay(request.getLastWorkingDay());
        }
        if (request.getNoticePaidNotWorked() != null) {
            instance.setNoticePaidNotWorked(request.getNoticePaidNotWorked());
        }

        OffsetDateTime now = OffsetDateTime.now();
        instance.setHrValidatedBy(actorId);
        instance.setHrValidatedAt(now);
        instance.setUpdatedAt(now);
        instanceRepo.save(instance);

        // Same reasoning as in updateDeclaration: IT chases equipment against this date.
        if (request.getLastWorkingDay() != null
                && !request.getLastWorkingDay().equals(previousLastWorkingDay)) {
            realignAssetReturnDates(instanceId, request.getLastWorkingDay());
        }

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_HR_VALIDATED", "OffboardingWorkflowInstance", instanceId,
            String.valueOf(previousLastWorkingDay),
            "lastWorkingDay=" + instance.getLastWorkingDay()
                + " noticePaidNotWorked=" + instance.getNoticePaidNotWorked());

        return toInstanceDto(instance);
    }

    private void assertActive(OffboardingWorkflowInstance instance) {
        if (!ACTIVE_STATUSES.contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Le dossier est " + instance.getStatus() + " — il n'est plus modifiable.");
        }
    }

    /**
     * Stage 2 requires stage 1. Enforced server-side and not only by the wizard's lock:
     * validating a departure whose date is not yet agreed is the thing the gate exists to
     * prevent, and the wizard is not the only possible caller.
     */
    private void assertDeclarationComplete(OffboardingWorkflowInstance instance) {
        if (instance.getLastWorkingDay() == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "La déclaration doit être complétée (dernier jour travaillé) avant la validation.");
        }
    }

    // ── 3. Skip a task ────────────────────────────────────────────────────────

    public OffboardingTaskDto skipTask(Long taskId, String reason, Long skippedBy) {
        OffboardingTask task = findTaskOrThrow(taskId);
        assertMayActOnTask(task);

        OffboardingWorkflowInstance owner = task.getWorkflowInstance();
        if (owner != null && !ACTIVE_STATUSES.contains(owner.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Le dossier est " + owner.getStatus() + " — ses tâches ne sont plus modifiables.");
        }

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

        moveLifecycle(instance, LifecycleStatus.TERMINATED,
            "Offboarding validé (workflow id=" + instanceId + ")", validatedBy);

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

        // Back to duty: a departure called off returns the employee to work.
        moveLifecycle(instance, LifecycleStatus.ACTIVE,
            "Offboarding annulé — " + reason, cancelledBy);

        auditService.log(cancelledBy != null ? cancelledBy.toString() : "SYSTEM",
            "OFFBOARDING_CANCELLED", "OffboardingWorkflowInstance", instanceId,
            null, "reason=" + reason);

        return toInstanceDto(instance);
    }

    // ── 5b. Reopen a closed file ──────────────────────────────────────────────

    /**
     * VALIDATED or CANCELLED → IN_PROGRESS.
     *
     * A file validated a day early, or cancelled by mistake, was terminal forever: the only
     * remedy was opening a fresh one and losing the whole task history. Restricted to
     * `RH_VALIDATE_OFFBOARDING` (the controller) and a reason is mandatory, because this
     * re-opens a record that has been treated as final.
     *
     * The lifecycle follows back to OFFBOARDING — the employee is not terminated any more, and
     * leaving the profile TERMINATED under a re-opened file is the inconsistency that matters
     * most here. Guarded: an ARCHIVED profile has been pseudonymised and cannot come back.
     */
    public OffboardingWorkflowInstanceDto reopenWorkflow(Long instanceId, String reason, Long actorId) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        if (!List.of("VALIDATED", "CANCELLED").contains(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Seuls les dossiers validés ou annulés peuvent être réouverts (statut actuel : "
                + instance.getStatus() + ").");
        }
        if (reason == null || reason.isBlank()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION,
                "Un motif est obligatoire pour réouvrir un dossier.");
        }

        String previousStatus = instance.getStatus();
        OffsetDateTime now = OffsetDateTime.now();

        instance.setStatus("IN_PROGRESS");
        // Clear the closure stamps: they described an outcome that no longer holds. The audit
        // log keeps them, which is where a superseded decision belongs.
        instance.setValidatedBy(null);
        instance.setValidatedAt(null);
        instance.setCompletionDate(null);
        instance.setCancelledBy(null);
        instance.setCancelledAt(null);
        instance.setCancellationReason(null);
        instance.setUpdatedAt(now);
        instanceRepo.save(instance);

        // TERMINATED → OFFBOARDING, newly allowed by the enum: without it the profile stayed
        // terminated under a reopened file, and validating again failed forever.
        moveLifecycle(instance, LifecycleStatus.OFFBOARDING,
            "Dossier d'offboarding réouvert — " + reason, actorId);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_REOPENED", "OffboardingWorkflowInstance", instanceId,
            previousStatus, "IN_PROGRESS | " + reason);

        return toInstanceDto(instance);
    }

    // ── 5c. Archive ───────────────────────────────────────────────────────────

    /**
     * VALIDATED → ARCHIVED. Retention, not deletion.
     *
     * `ARCHIVED` has been in the status CHECK constraint since V44 with no code path, so the
     * working set grew forever and the status was decorative. Archiving takes the file out of
     * the list and the board (see `findActiveByPays`) while leaving every row intact.
     *
     * Only from VALIDATED: archiving a cancelled file would hide the fact that a departure was
     * called off, and archiving an open one would lose live work.
     */
    public OffboardingWorkflowInstanceDto archiveWorkflow(Long instanceId, Long actorId) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);

        if (!"VALIDATED".equals(instance.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "Seul un dossier validé peut être archivé (statut actuel : "
                + instance.getStatus() + ").");
        }

        instance.setStatus("ARCHIVED");
        instance.setUpdatedAt(OffsetDateTime.now());
        instanceRepo.save(instance);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "OFFBOARDING_ARCHIVED", "OffboardingWorkflowInstance", instanceId,
            "VALIDATED", "ARCHIVED");

        return toInstanceDto(instance);
    }

    // ── 6. Save exit interview ────────────────────────────────────────────────

    /**
     * Records the interview — or updates the record that is already there.
     *
     * It used to throw ALREADY_EXISTS on a second call, while the UI prefilled the existing
     * record for editing: correcting a typo in the feedback was impossible, and the user got
     * a 409 for using the form as it presented itself. There is still exactly one interview
     * per workflow (UX_exit_workflow); this makes the endpoint an upsert instead of a
     * create-only, which is what a single-row-per-parent resource wants.
     *
     * A row created by `scheduleExitInterview` is the normal case here — booking then holding
     * the interview is the intended sequence, and this is the step that closes it.
     */
    public ExitInterviewDto saveExitInterview(Long instanceId, ExitInterviewRequestDto request,
                                              Long conductedBy) {
        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

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
        ExitInterview interview = interviewRepo.findByWorkflowInstanceId(instanceId)
            .orElseGet(() -> ExitInterview.builder()
                .workflowInstance(instance)
                .isAnonymised(false)
                .createdAt(now)
                .build());

        interview.setConductedBy(conductedBy);
        interview.setConductedDate(request.getConductedDate());
        interview.setDepartureReasons(reasonsJson);
        interview.setFeedbackText(request.getFeedbackText());
        interview.setStatus("DONE");
        if (interview.getId() != null) interview.setUpdatedAt(now);

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

    /**
     * Books (or re-books) the interview. The design's **Planifier**, which had no endpoint.
     *
     * Does NOT complete the EXIT_INTERVIEW task — scheduling is a promise, not the act. The
     * task closes when `saveExitInterview` records what was said.
     */
    public ExitInterviewDto scheduleExitInterview(
            Long instanceId, ScheduleExitInterviewDto request, Long actorId) {

        OffboardingWorkflowInstance instance = findInstanceOrThrow(instanceId);
        assertActive(instance);

        OffsetDateTime now = OffsetDateTime.now();
        ExitInterview interview = interviewRepo.findByWorkflowInstanceId(instanceId)
            .orElseGet(() -> ExitInterview.builder()
                .workflowInstance(instance)
                .isAnonymised(false)
                .createdAt(now)
                .build());

        if ("DONE".equals(interview.getStatus())) {
            throw new AppException(ErrorCode.INVALID_TRANSITION,
                "L'entretien de sortie a déjà eu lieu — il ne peut plus être planifié.");
        }

        interview.setScheduledAt(request.getScheduledAt());
        interview.setStatus("SCHEDULED");
        if (interview.getId() != null) interview.setUpdatedAt(now);
        interview = interviewRepo.save(interview);

        auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
            "EXIT_INTERVIEW_SCHEDULED", "ExitInterview", interview.getId(),
            null, "workflowId=" + instanceId + " at=" + request.getScheduledAt());

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

        completeAssetTaskIfAllReturned(asset.getWorkflowInstanceId(), confirmedBy);

        return toAssetDto(asset);
    }

    /**
     * Closes `ASSET_RETURN_IT` once every tracked asset is back.
     *
     * Same shape as saving an exit interview completing `EXIT_INTERVIEW`: the task IS the
     * inventory being clear, so making someone confirm the last laptop and then also tick
     * the task is a second source of truth that can disagree with the first.
     *
     * This matters more than a convenience: `ASSET_RETURN_IT` is `is_blocking`, and
     * `validateWorkflow` refuses while a blocking task is open. Nothing in the UI could
     * complete it, so before this no file could ever be validated.
     *
     * Written-off assets count as settled — the point of a write-off is that the item is
     * not coming back. An instance with no tracked assets is NOT auto-completed: "nothing
     * to return" is indistinguishable from "the IT provisioning record was never synced",
     * and the second must stay a deliberate human decision.
     */
    private void completeAssetTaskIfAllReturned(Long instanceId, Long actorId) {
        try {
            List<OffboardingAssetReturn> assets = assetRepo.findByWorkflowInstanceId(instanceId);
            if (assets.isEmpty()) return;

            boolean allSettled = assets.stream().allMatch(
                a -> a.getActualReturnDate() != null || Boolean.TRUE.equals(a.getIsWrittenOff()));
            if (!allSettled) return;

            taskRepo.findByWorkflowInstanceId(instanceId).stream()
                .filter(t -> "ASSET_RETURN_IT".equals(t.getTaskCode())
                          && !"DONE".equals(t.getStatus())
                          && !"SKIPPED".equals(t.getStatus()))
                .findFirst()
                .ifPresent(task -> {
                    OffsetDateTime now = OffsetDateTime.now();
                    task.setStatus("DONE");
                    task.setCompletedBy(actorId);
                    task.setCompletedAt(now);
                    task.setComments("Tous les équipements ont été restitués");
                    taskRepo.save(task);

                    auditService.log(actorId != null ? actorId.toString() : "SYSTEM",
                        "OFFBOARDING_TASK_DONE", "OffboardingTask", task.getId(),
                        null, "auto: all assets returned, workflowId=" + instanceId);

                    // It was the last blocking task ⇒ the file is no longer BLOCKED.
                    OffboardingWorkflowInstance instance = task.getWorkflowInstance();
                    if (instance != null && "BLOCKED".equals(instance.getStatus())
                            && taskRepo.findBlockingIncomplete(instanceId).isEmpty()) {
                        instance.setStatus("IN_PROGRESS");
                        instance.setUpdatedAt(now);
                        instanceRepo.save(instance);
                    }
                });
        } catch (Exception ex) {
            // Never fail the return itself over the bookkeeping that follows it.
            log.error("Could not auto-complete ASSET_RETURN_IT for instance {}: {}",
                instanceId, ex.getMessage(), ex);
        }
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

    /** Null when none has been recorded — an absent optional sub-resource, not an error. */
    @Transactional(readOnly = true)
    public ExitInterviewDto findExitInterview(Long instanceId) {
        return interviewRepo.findByWorkflowInstanceId(instanceId)
            .map(this::toInterviewDto)
            .orElse(null);
    }

    // ── Lifecycle coupling ────────────────────────────────────────────────────

    /**
     * Moves the employee's lifecycle to `target`, and NEVER fails the workflow action over it.
     *
     * Three call sites need this — validate (→ TERMINATED), cancel (→ ACTIVE) and reopen
     * (→ OFFBOARDING) — and each used to inline a bare `transitionLifecycleByActor`, which
     * throws on any disallowed move. Since the service is `@Transactional`, that rolled the
     * whole action back: a profile already sitting on the target made the workflow permanently
     * unclosable with "Transition interdite: TERMINATED → TERMINATED".
     *
     * Two distinct cases, and neither should abort the caller:
     *   - ALREADY there → nothing to do. Being terminated twice is not an error; that is what
     *     idempotency means, and the profile could have been set by hand from its own page
     *     (OFFBOARDING → TERMINATED is offered there).
     *   - NOT REACHABLE → log a warning and carry on. The workflow status is the record of the
     *     offboarding; the lifecycle is derived state, visible and correctable on the profile.
     */
    private void moveLifecycle(OffboardingWorkflowInstance instance, LifecycleStatus target,
                               String reason, Long actorId) {
        moveLifecycleForProfile(instance.getEmployeeProfileId(), target, reason, actorId,
            instance.getId());
    }

    /** Same contract, addressable before the instance exists (startOffboarding). */
    private void moveLifecycleForProfile(Long profileId, LifecycleStatus target,
                                         String reason, Long actorId, Long instanceId) {
        try {
            LifecycleStatus current = profileRepo.findById(profileId)
                .map(EmployeeProfile::getLifecycleStatus).orElse(null);

            if (current == target) {
                log.debug("Profile {} is already {} — lifecycle move skipped (workflow {})",
                    profileId, target, instanceId);
                return;
            }
            if (current != null && !current.canTransitionTo(target)) {
                log.warn("Profile {} cannot move {} → {}; workflow {} proceeds and the lifecycle "
                       + "stays as it is. Correct it from the profile page if needed.",
                    profileId, current, target, instanceId);
                return;
            }

            LifecycleTransitionDto dto = new LifecycleTransitionDto();
            dto.setNewStatus(target);
            dto.setReason(reason);
            profileService.transitionLifecycleByActor(profileId, dto, actorId);
        } catch (Exception ex) {
            log.warn("Lifecycle move to {} failed for profileId={} (workflow {} proceeds anyway): {}",
                target, profileId, instanceId, ex.getMessage());
        }
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    /**
     * A task may be completed or skipped by the department that owns its stage, or by RH.
     *
     * The controller cannot express this in `@PreAuthorize`: which permission applies
     * depends on the task's `task_code`, which is only known after loading the row. So
     * the check lives here, reading the authorities the JWT filter put on the context —
     * the same strings `HrPermissionEvaluator` compares against.
     *
     * `RH_MANAGE_OFFBOARDING` always passes: RH owns the file end to end and has to be
     * able to act for a department that is unavailable.
     */
    private void assertMayActOnTask(OffboardingTask task) {
        String required = OffboardingStagePermissions.forTaskCode(task.getTaskCode());
        if (hasAuthority(required) || hasAuthority(PermissionCatalog.RH_MANAGE_OFFBOARDING)) {
            return;
        }
        throw new AppException(ErrorCode.FORBIDDEN,
            "Cette étape est réservée à son service (permission requise : " + required + ").");
    }

    private boolean hasAuthority(String code) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(code));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Loads an instance and asserts it belongs to the caller's pays.
     *
     * Only `list` and `start` were tenant-scoped, so every by-id path — get, tasks, assets,
     * the exit interview, complete, skip, confirm-return — would happily serve another
     * country's file to anyone who guessed an id. Answers NOT_FOUND rather than FORBIDDEN on
     * purpose: whether id 20002 exists in another pays is itself not the caller's business.
     *
     * `getEffectivePaysId()` is null for users who legitimately see every entity (admins,
     * roles flagged show-all), and that skips the check — the same convention `list` uses.
     */
    private OffboardingWorkflowInstance findInstanceOrThrow(Long instanceId) {
        OffboardingWorkflowInstance instance = instanceRepo.findById(instanceId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Workflow d'offboarding introuvable: id=" + instanceId));

        Long callerPaysId = tenantService.getEffectivePaysId();
        if (callerPaysId != null && !callerPaysId.equals(instance.getPaysId())) {
            log.warn("Cross-pays offboarding access refused: instance {} (pays {}) requested by pays {}",
                instanceId, instance.getPaysId(), callerPaysId);
            throw new AppException(ErrorCode.NOT_FOUND,
                "Workflow d'offboarding introuvable: id=" + instanceId);
        }
        return instance;
    }

    private OffboardingTask findTaskOrThrow(Long taskId) {
        return taskRepo.findById(taskId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND,
                "Tâche d'offboarding introuvable: id=" + taskId));
    }

    private String resolveContractType(Long contractId) {
        return jdbc.queryForObject(CONTRACT_TYPE_SQL, String.class, contractId);
    }

    /** Null when the profile has no contract in force — the caller then logs and uses CDI. */
    private Long findActiveContractId(Long profileId) {
        try {
            List<Long> ids = jdbc.queryForList(ACTIVE_CONTRACT_SQL, Long.class, profileId);
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Exception ex) {
            log.warn("Could not resolve the active contract for profileId={}: {}",
                profileId, ex.getMessage());
            return null;
        }
    }

    private String resolveEmployeeName(Long profileId) {
        try {
            return jdbc.queryForObject(EMPLOYEE_NAME_SQL, String.class, profileId);
        } catch (Exception ex) {
            log.debug("Could not resolve name for profileId={}: {}", profileId, ex.getMessage());
            return null;
        }
    }

    /** Null-in / null-out, so an unstamped validation costs no query at all. */
    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(USER_NAME_SQL, String.class, userId);
        } catch (Exception ex) {
            log.debug("Could not resolve name for userId={}: {}", userId, ex.getMessage());
            return null;
        }
    }

    private Long resolveHandoverManagerUserId(Long managerProfileId) {
        if (managerProfileId == null) return null;
        try {
            return profileService.getUserId(managerProfileId);
        } catch (Exception ex) {
            log.debug("Could not resolve userId for handover manager profileId={}: {}",
                managerProfileId, ex.getMessage());
            return null;
        }
    }

    /** Name, gender and photo flag for the avatar. Never throws — degrades to nulls. */
    private record EmployeeIdentity(String fullName, String gender, String photoUrl) {}

    private EmployeeIdentity resolveEmployeeIdentity(Long profileId) {
        if (profileId == null) return new EmployeeIdentity(null, null, null);
        try {
            return jdbc.queryForObject(EMPLOYEE_IDENTITY_SQL,
                (rs, rowNum) -> new EmployeeIdentity(
                    rs.getString("full_name"), rs.getString("gender"), rs.getString("photo_url")),
                profileId);
        } catch (Exception ex) {
            log.debug("Could not resolve identity for profileId={}: {}", profileId, ex.getMessage());
            return new EmployeeIdentity(null, null, null);
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
                .map(a -> OffboardingAssetReturn.builder()
                    .workflowInstanceId(instance.getId())
                    .assetDescription(describeItAsset(a))
                    // Its own column since V61 rather than appended to the description:
                    // the design shows them as two lines, and a formatted string is a bad
                    // de-duplication key (see reseedItAssets).
                    .serialNumber(a.getSerialNumber() != null && !a.getSerialNumber().isBlank()
                        ? a.getSerialNumber().trim() : null)
                    .assetType("IT")
                    .expectedReturnDate(expectedReturn)
                    .isWrittenOff(false)
                    .isUrgent(false)
                    .createdAt(OffsetDateTime.now())
                    .build())
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

        /*
         * De-duplicate on the SERIAL NUMBER where there is one, and only fall back to the
         * description otherwise.
         *
         * This used to key on the concatenated description, so re-syncing after any change to
         * the label format — a renamed it_asset_type, a corrected brand — produced a second
         * row for the same physical laptop. A serial identifies the object; a display string
         * describes it.
         */
        List<OffboardingAssetReturn> tracked = assetRepo.findByWorkflowInstanceId(instanceId);
        Set<String> existingSerials = tracked.stream()
            .map(OffboardingAssetReturn::getSerialNumber)
            .filter(s -> s != null && !s.isBlank())
            .map(String::trim)
            .collect(Collectors.toSet());
        Set<String> existingDescriptions = tracked.stream()
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
            .filter(a -> {
                String serial = a.getSerialNumber() != null ? a.getSerialNumber().trim() : "";
                return serial.isBlank()
                    ? !existingDescriptions.contains(describeItAsset(a))
                    : !existingSerials.contains(serial);
            })
            .map(a -> OffboardingAssetReturn.builder()
                .workflowInstanceId(instanceId)
                .assetDescription(describeItAsset(a))
                .serialNumber(a.getSerialNumber() != null && !a.getSerialNumber().isBlank()
                    ? a.getSerialNumber().trim() : null)
                .assetType("IT")
                .expectedReturnDate(expectedReturn)
                .isWrittenOff(false)
                .isUrgent(false)
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
     * "MacBook Pro 14 (Ordinateur portable)" — brand/model with the type in brackets, or the
     * type alone. No serial: that has its own column since V61, and both the seed and the
     * re-sync must format it identically or the de-dupe fallback misses.
     */
    private String describeItAsset(ItAsset a) {
        String label = a.getAssetType() != null ? a.getAssetType().getLabelFr() : "Équipement IT";
        String brandModel = a.getBrandModel() != null ? a.getBrandModel().trim() : "";
        return brandModel.isEmpty() ? label : brandModel + " (" + label + ")";
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

        EmployeeIdentity identity = resolveEmployeeIdentity(w.getEmployeeProfileId());
        String handoverManagerName = (w.getHandoverManagerProfileId() != null)
            ? resolveEmployeeName(w.getHandoverManagerProfileId())
            : null;

        return OffboardingWorkflowInstanceDto.builder()
            .id(w.getId())
            .paysId(w.getPaysId())
            .employeeProfileId(w.getEmployeeProfileId())
            .employeeFullName(identity.fullName())
            .employeeGender(identity.gender())
            .employeePhotoUrl(identity.photoUrl())
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
            .handoverManagerUserId(resolveHandoverManagerUserId(w.getHandoverManagerProfileId()))
            // Stage 2 — Validation Manager & RH (V59). The name lookups short-circuit on
            // null, so an unvalidated file adds no query.
            .managerValidatedBy(w.getManagerValidatedBy())
            .managerValidatedByName(resolveUserName(w.getManagerValidatedBy()))
            .managerValidatedAt(w.getManagerValidatedAt())
            .managerComment(w.getManagerComment())
            .hrValidatedBy(w.getHrValidatedBy())
            .hrValidatedByName(resolveUserName(w.getHrValidatedBy()))
            .hrValidatedAt(w.getHrValidatedAt())
            .noticePaidNotWorked(w.getNoticePaidNotWorked())
            // Stage 3 — Passation (V60)
            .handoverMinutesUrl(w.getHandoverMinutesUrl())
            .handoverMinutesName(w.getHandoverMinutesName())
            // Stage 4 — Informatique & Matériel (V61)
            .accountDeactivationAt(w.getAccountDeactivationAt())
            .dischargeDocumentUrl(w.getDischargeDocumentUrl())
            .dischargeDocumentName(w.getDischargeDocumentName())
            // Stage 6 — Solde de tout compte (V63). `settlement` stays null with no lines, so
            // the stage keeps rendering its own empty state rather than a zero total.
            .settlementExecutionDate(w.getSettlementExecutionDate())
            .settlement(settlementRepo.existsByWorkflowInstanceId(w.getId())
                ? buildSettlement(w.getId()) : null)
            .settlementPaymentMode(resolveSettlementPaymentMode(w.getEmployeeProfileId()))
            .checklistItems(
                checklistRepo.findByWorkflowInstanceIdOrderByGroupCodeAscOrderIndexAsc(w.getId())
                    .stream().map(this::toChecklistDto).collect(Collectors.toList()))
            // Stage 1 — Déclaration (V57)
            .justificationDocumentUrl(w.getJustificationDocumentUrl())
            .justificationDocumentName(w.getJustificationDocumentName())
            .noticePeriodLabel(w.getNoticePeriodLabel())
            .noticeWaiverRequested(w.getNoticeWaiverRequested())
            .theoreticalExitDate(w.getTheoreticalExitDate())
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
            .conductedByName(resolveUserName(e.getConductedBy()))
            .conductedDate(e.getConductedDate())
            .status(e.getStatus())
            .scheduledAt(e.getScheduledAt())
            .departureReasons(e.getDepartureReasons())
            .feedbackText(e.getFeedbackText())
            .isAnonymised(e.getIsAnonymised())
            .anonymisedAt(e.getAnonymisedAt())
            .visibleToRoles(e.getVisibleToRoles())
            .createdAt(e.getCreatedAt())
            .updatedAt(e.getUpdatedAt())
            .build();
    }

    /** `group`, not `groupCode` — the frontend model names it `group`. */
    private OffboardingChecklistItemDto toChecklistDto(OffboardingChecklistItem i) {
        return OffboardingChecklistItemDto.builder()
            .id(i.getId())
            .group(i.getGroupCode())
            .code(i.getItemCode())
            .label(i.getItemLabel())
            .isDone(i.getIsDone())
            .documentUrl(i.getDocumentUrl())
            .completedByName(resolveUserName(i.getCompletedBy()))
            .orderIndex(i.getOrderIndex())
            .build();
    }

    private OffboardingAssetReturnDto toAssetDto(OffboardingAssetReturn a) {
        return OffboardingAssetReturnDto.builder()
            .id(a.getId())
            .workflowInstanceId(a.getWorkflowInstanceId())
            .taskId(a.getTaskId())
            .assetDescription(a.getAssetDescription())
            .assetType(a.getAssetType())
            .serialNumber(a.getSerialNumber())
            .isUrgent(a.getIsUrgent())
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
