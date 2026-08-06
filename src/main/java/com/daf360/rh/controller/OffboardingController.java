package com.daf360.rh.controller;

import com.daf360.rh.dto.offboarding.*;
import com.daf360.rh.service.OffboardingWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
/*
 * Class level is the READ gate, and it mirrors the frontend route guard on
 * /rh/offboarding exactly. It used to be `RH_MANAGE_OFFBOARDING` for everything, which
 * meant only DRH and Administrateur could reach any endpoint — so the departments that
 * own the work (IT returning equipment, finance settling the STC, a manager closing a
 * passation) could not do it, and RH had to click on their behalf.
 *
 * Every MUTATING endpoint now carries its own permission:
 *   - stages 1, 2, 5 and 7 are RH  → RH_MANAGE_OFFBOARDING / RH_VALIDATE_OFFBOARDING
 *                                     / RH_CONDUCT_EXIT_INTERVIEW
 *   - stage 4 (IT & Matériel)      → RH_OFFBOARDING_STAGE_IT
 *   - task complete/skip           → resolved from the task's own code, in the service:
 *                                     the required permission is not knowable until the
 *                                     row is loaded, so @PreAuthorize cannot express it
 *                                     (see OffboardingStagePermissions).
 * V58 grants RH_VIEW_CONTRACTS to the stage-owner roles so they clear this read gate.
 */
@PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING') "
            + "or hasPermission(null, 'RH_VIEW_CONTRACTS') "
            + "or hasPermission(null, 'RH_MANAGE_LIFECYCLE')")
public class OffboardingController {

    private final OffboardingWorkflowService offboardingService;

    // ── Workflow instances ────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto startOffboarding(
            @Valid @RequestBody StartOffboardingRequestDto request,
            Authentication auth) {
        return offboardingService.startOffboarding(request, actorId(auth));
    }

    /**
     * Stage 1 — fills in the declaration (dates, notice, justification).
     *
     * A file opened from a profile carries only a departure type, so this is what
     * completes stage 1 and unlocks the stages after it. RH-only: the declaration is
     * an RH act, and V58 leaves stages 1/2/7 on the existing RH codes.
     */
    @PatchMapping("/api/hr/offboarding/{instanceId}/declaration")
    @PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto updateDeclaration(
            @PathVariable Long instanceId,
            @Valid @RequestBody UpdateDeclarationRequestDto request,
            Authentication auth) {
        return offboardingService.updateDeclaration(instanceId, request, actorId(auth));
    }

    @GetMapping("/api/hr/offboarding")
    public List<OffboardingWorkflowInstanceDto> listWorkflowInstances(
            @RequestParam(required = false) Long paysId,
            @RequestParam(required = false) String status) {
        return offboardingService.listWorkflowInstances(paysId, status);
    }

    @GetMapping("/api/hr/offboarding/{instanceId}")
    public OffboardingWorkflowInstanceDto getWorkflowInstance(
            @PathVariable Long instanceId) {
        return offboardingService.getWorkflowInstance(instanceId);
    }

    @GetMapping("/api/hr/offboarding/{instanceId}/tasks")
    public List<OffboardingTaskDto> listTasks(@PathVariable Long instanceId) {
        return offboardingService.listTasks(instanceId);
    }

    // ── Task actions ──────────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding/tasks/{taskId}/complete")
    public OffboardingTaskDto completeTask(
            @PathVariable Long taskId,
            @RequestBody CompleteTaskRequestDto request,
            Authentication auth) {
        return offboardingService.completeTask(taskId, request, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/tasks/{taskId}/skip")
    public OffboardingTaskDto skipTask(
            @PathVariable Long taskId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String reason = body != null ? body.get("reason") : null;
        return offboardingService.skipTask(taskId, reason, actorId(auth));
    }

    // ── Stage 3 — Passation ───────────────────────────────────────────────────

    /**
     * Names the successor, sets the passation window and records the PV.
     *
     * Only the class-level read gate here since V65: stage 3 belongs to the departing
     * employee's manager, and "is the caller that person" depends on the instance — the
     * annotation cannot load it. `assertMayManageHandover` accepts the named handover manager,
     * a hierarchical manager of the employee, or a holder of the stage/RH permission.
     */
    @PatchMapping("/api/hr/offboarding/{instanceId}/handover")
    public OffboardingWorkflowInstanceDto updateHandover(
            @PathVariable Long instanceId,
            @Valid @RequestBody UpdateHandoverRequestDto request,
            Authentication auth) {
        return offboardingService.updateHandover(instanceId, request, actorId(auth));
    }

    // ── Stage 4 — Informatique & Matériel ─────────────────────────────────────

    /** When the accounts go off. The IT officer's own field. */
    @PatchMapping("/api/hr/offboarding/{instanceId}/it-security")
    @PreAuthorize("hasPermission(null, 'RH_OFFBOARDING_STAGE_IT') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto updateItSecurity(
            @PathVariable Long instanceId,
            @Valid @RequestBody UpdateItSecurityRequestDto request,
            Authentication auth) {
        return offboardingService.updateItSecurity(instanceId, request, actorId(auth));
    }

    /**
     * Generates the décharge de matériel from the live asset list and stores it on the file.
     * Re-runnable: a late return means re-generating gives a correct document.
     */
    @PostMapping("/api/hr/offboarding/{instanceId}/documents/discharge")
    @PreAuthorize("hasPermission(null, 'RH_OFFBOARDING_STAGE_IT') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto generateDischarge(
            @PathVariable Long instanceId, Authentication auth) {
        return offboardingService.generateDischarge(instanceId, actorId(auth));
    }

    // ── Checklists (stages 3, 4, 5) ───────────────────────────────────────────
    // Only the class-level read gate here: which permission applies depends on the item's
    // GROUP, which is only known once the row is loaded — so the check is in the service
    // (see OffboardingStagePermissions.forChecklistGroup).

    @PatchMapping("/api/hr/offboarding/checklist-items/{itemId}")
    public OffboardingChecklistItemDto updateChecklistItem(
            @PathVariable Long itemId,
            @RequestBody UpdateChecklistItemDto request,
            Authentication auth) {
        return offboardingService.updateChecklistItem(itemId, request, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/checklist-items")
    @ResponseStatus(HttpStatus.CREATED)
    public OffboardingChecklistItemDto addChecklistItem(
            @PathVariable Long instanceId,
            @Valid @RequestBody CreateChecklistItemDto request,
            Authentication auth) {
        return offboardingService.addChecklistItem(instanceId, request, actorId(auth));
    }

    @DeleteMapping("/api/hr/offboarding/checklist-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChecklistItem(@PathVariable Long itemId, Authentication auth) {
        offboardingService.deleteChecklistItem(itemId, actorId(auth));
    }

    // ── Stage 2 — Validation Manager & RH ─────────────────────────────────────

    /**
     * The manager's decision. Only the class-level read gate here: the person allowed to
     * do this is the file's *named* handover manager, which needs the row loaded, so the
     * check lives in the service (RH may stand in — see `validateAsManager`).
     */
    @PostMapping("/api/hr/offboarding/{instanceId}/validation/manager")
    public OffboardingWorkflowInstanceDto validateAsManager(
            @PathVariable Long instanceId,
            @Valid @RequestBody ManagerValidationRequestDto request,
            Authentication auth) {
        return offboardingService.validateAsManager(instanceId, request, actorId(auth));
    }

    /** RH's validation and date adjustment. Refused until the manager has stamped. */
    @PostMapping("/api/hr/offboarding/{instanceId}/validation/hr")
    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_OFFBOARDING') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto validateAsHr(
            @PathVariable Long instanceId,
            @Valid @RequestBody HrValidationRequestDto request,
            Authentication auth) {
        return offboardingService.validateAsHr(instanceId, request, actorId(auth));
    }

    // ── Workflow lifecycle ────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding/{instanceId}/validate")
    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto validateWorkflow(
            @PathVariable Long instanceId,
            Authentication auth) {
        return offboardingService.validateWorkflow(instanceId, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/cancel")
    @PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto cancelWorkflow(
            @PathVariable Long instanceId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String reason = body != null ? body.get("reason") : null;
        return offboardingService.cancelWorkflow(instanceId, reason, actorId(auth));
    }

    // ── Audit trail ───────────────────────────────────────────────────────────

    /**
     * The file's real history. The drawer used to rebuild an approximation from the DTO's
     * surviving timestamps, so skipped tasks, deleted lines and corrected amounts were invisible.
     */
    @GetMapping("/api/hr/offboarding/{instanceId}/audit")
    public List<OffboardingAuditEntryDto> auditTrail(@PathVariable Long instanceId) {
        return offboardingService.getAuditTrail(instanceId);
    }

    @GetMapping("/api/hr/offboarding/{instanceId}/audit.csv")
    public ResponseEntity<String> auditTrailCsv(@PathVariable Long instanceId) {
        return ResponseEntity.ok()
            // BOM so Excel opens the accents correctly rather than as mojibake.
            .header("Content-Type", "text/csv; charset=UTF-8")
            .header("Content-Disposition",
                "attachment; filename=\"offboarding-" + instanceId + "-audit.csv\"")
            .body("﻿" + offboardingService.getAuditTrailCsv(instanceId));
    }

    // ── Stage 5 — Kit RH documents ────────────────────────────────────────────

    /** Generates one Kit RH document and ticks its checklist line. */
    @PostMapping("/api/hr/offboarding/{instanceId}/documents/kit/{itemCode}")
    public OffboardingChecklistItemDto generateKitDocument(
            @PathVariable Long instanceId,
            @PathVariable String itemCode,
            Authentication auth) {
        return offboardingService.generateKitDocument(instanceId, itemCode, actorId(auth));
    }

    /** Every generated Kit RH document, zipped. */
    @GetMapping("/api/hr/offboarding/{instanceId}/documents/kit")
    public ResponseEntity<byte[]> downloadKitArchive(@PathVariable Long instanceId) {
        byte[] zip = offboardingService.buildKitArchive(instanceId);
        return ResponseEntity.ok()
            .header("Content-Type", "application/zip")
            .header("Content-Disposition",
                "attachment; filename=\"kit-rh-" + instanceId + ".zip\"")
            .body(zip);
    }

    /**
     * The file's own documents, streamed: the décharge (stage 4), the PV de passation (stage 3)
     * and the justification (stage 1). They all exist because the stored value is a server-side
     * file path — rh-service serves no static resources, so the UI's `href` to it could never
     * download anything.
     *
     * `discharge` shares its path with the POST that generates it; the verb tells them apart.
     */
    @GetMapping("/api/hr/offboarding/{instanceId}/documents/{kind:discharge|minutes|justification}")
    public ResponseEntity<byte[]> downloadInstanceDocument(@PathVariable Long instanceId,
                                                           @PathVariable String kind) {
        return fileResponse(offboardingService.readInstanceDocument(instanceId, kind));
    }

    @GetMapping("/api/hr/offboarding/checklist-items/{itemId}/document")
    public ResponseEntity<byte[]> downloadChecklistDocument(@PathVariable Long itemId) {
        return fileResponse(offboardingService.readChecklistDocument(itemId));
    }

    private ResponseEntity<byte[]> fileResponse(
            com.daf360.rh.service.OffboardingWorkflowService.StoredFile file) {
        return ResponseEntity.ok()
            .header("Content-Type", file.contentType())
            .header("Content-Disposition",
                "attachment; filename=\"" + file.filename() + "\"")
            .body(file.bytes());
    }

    // ── Stage 6 — Solde de tout compte ────────────────────────────────────────
    // Permission is checked in the service (RH_OFFBOARDING_STAGE_PAYROLL or RH), so the line
    // endpoints can be addressed by line id without the instance in the path.

    @PatchMapping("/api/hr/offboarding/{instanceId}/settlement")
    public OffboardingWorkflowInstanceDto updateSettlement(
            @PathVariable Long instanceId,
            @Valid @RequestBody UpdateSettlementRequestDto request,
            Authentication auth) {
        return offboardingService.updateSettlement(instanceId, request, actorId(auth));
    }

    /** Seeds the breakdown: prorata 13ᵉ mois computed, the other two as blanks to fill. */
    @PostMapping("/api/hr/offboarding/{instanceId}/settlement/suggest")
    public OffboardingSettlementDto suggestSettlement(
            @PathVariable Long instanceId, Authentication auth) {
        return offboardingService.suggestSettlement(instanceId, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/settlement/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public OffboardingSettlementDto addSettlementLine(
            @PathVariable Long instanceId,
            @Valid @RequestBody SaveSettlementLineDto request,
            Authentication auth) {
        return offboardingService.addSettlementLine(instanceId, request, actorId(auth));
    }

    @PatchMapping("/api/hr/offboarding/settlement/lines/{lineId}")
    public OffboardingSettlementDto updateSettlementLine(
            @PathVariable Long lineId,
            @Valid @RequestBody SaveSettlementLineDto request,
            Authentication auth) {
        return offboardingService.updateSettlementLine(lineId, request, actorId(auth));
    }

    @DeleteMapping("/api/hr/offboarding/settlement/lines/{lineId}")
    public OffboardingSettlementDto deleteSettlementLine(
            @PathVariable Long lineId, Authentication auth) {
        return offboardingService.deleteSettlementLine(lineId, actorId(auth));
    }

    // ── Stage 7 — Reopen / Archive ────────────────────────────────────────────

    /** Un-closes a file. Same right as validating it — and a reason is mandatory. */
    @PostMapping("/api/hr/offboarding/{instanceId}/reopen")
    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto reopenWorkflow(
            @PathVariable Long instanceId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String reason = body != null ? body.get("reason") : null;
        return offboardingService.reopenWorkflow(instanceId, reason, actorId(auth));
    }

    /** Retention: takes a validated file out of the working set without deleting anything. */
    @PostMapping("/api/hr/offboarding/{instanceId}/archive")
    @PreAuthorize("hasPermission(null, 'RH_VALIDATE_OFFBOARDING')")
    public OffboardingWorkflowInstanceDto archiveWorkflow(
            @PathVariable Long instanceId, Authentication auth) {
        return offboardingService.archiveWorkflow(instanceId, actorId(auth));
    }

    // ── Exit interview ────────────────────────────────────────────────────────

    /** The design's **Planifier** — books or re-books, without recording an outcome. */
    @PostMapping("/api/hr/offboarding/{instanceId}/exit-interview/schedule")
    @PreAuthorize("hasPermission(null, 'RH_CONDUCT_EXIT_INTERVIEW') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public ExitInterviewDto scheduleExitInterview(
            @PathVariable Long instanceId,
            @Valid @RequestBody ScheduleExitInterviewDto request,
            Authentication auth) {
        return offboardingService.scheduleExitInterview(instanceId, request, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/exit-interview")
    @ResponseStatus(HttpStatus.CREATED)
    // Stage 5 (Kit RH) is RH. `RH_CONDUCT_EXIT_INTERVIEW` existed since V44 and was never
    // enforced anywhere — this is the endpoint it was always meant to guard.
    @PreAuthorize("hasPermission(null, 'RH_CONDUCT_EXIT_INTERVIEW') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public ExitInterviewDto saveExitInterview(
            @PathVariable Long instanceId,
            @Valid @RequestBody ExitInterviewRequestDto request,
            Authentication auth) {
        return offboardingService.saveExitInterview(instanceId, request, actorId(auth));
    }

    /**
     * 204 when no exit interview has been recorded yet.
     *
     * Not having one is the NORMAL state for most of a workflow's life — the case page
     * loads this on every open, so a 404 logged a console error for every file that had
     * simply not reached the Kit RH stage. 404 means "this URL is wrong"; an absent
     * optional sub-resource is 204.
     */
    @GetMapping("/api/hr/offboarding/{instanceId}/exit-interview")
    public ResponseEntity<ExitInterviewDto> getExitInterview(@PathVariable Long instanceId) {
        ExitInterviewDto dto = offboardingService.findExitInterview(instanceId);
        return dto != null ? ResponseEntity.ok(dto) : ResponseEntity.noContent().build();
    }

    // ── Asset returns ─────────────────────────────────────────────────────────

    @GetMapping("/api/hr/offboarding/{instanceId}/assets")
    public List<OffboardingAssetReturnDto> listAssetReturns(@PathVariable Long instanceId) {
        return offboardingService.listAssetReturns(instanceId);
    }

    // The three asset mutations are stage 4 — the IT officer's own work.

    @PostMapping("/api/hr/offboarding/{instanceId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_OFFBOARDING_STAGE_IT') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingAssetReturnDto addAssetReturn(
            @PathVariable Long instanceId,
            @Valid @RequestBody CreateAssetReturnDto dto) {
        dto.setWorkflowInstanceId(instanceId);
        return offboardingService.addAssetReturn(instanceId, dto);
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/assets/sync-from-it")
    @PreAuthorize("hasPermission(null, 'RH_OFFBOARDING_STAGE_IT') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public List<OffboardingAssetReturnDto> reseedItAssets(@PathVariable Long instanceId) {
        return offboardingService.reseedItAssets(instanceId);
    }

    @PatchMapping("/api/hr/offboarding/assets/{assetId}/confirm-return")
    @PreAuthorize("hasPermission(null, 'RH_OFFBOARDING_STAGE_IT') "
                + "or hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
    public OffboardingAssetReturnDto confirmAssetReturn(
            @PathVariable Long assetId,
            @RequestBody ConfirmAssetReturnDto dto,
            Authentication auth) {
        return offboardingService.confirmAssetReturn(assetId, dto, actorId(auth));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
