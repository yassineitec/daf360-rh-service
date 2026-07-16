package com.daf360.rh.controller;

import com.daf360.rh.dto.offboarding.*;
import com.daf360.rh.service.OffboardingWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OffboardingController {

    private final OffboardingWorkflowService offboardingService;

    // ── Workflow instances ────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding")
    @ResponseStatus(HttpStatus.CREATED)
    public OffboardingWorkflowInstanceDto startOffboarding(
            @Valid @RequestBody StartOffboardingRequestDto request,
            Authentication auth) {
        return offboardingService.startOffboarding(request, actorId(auth));
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

    // ── Workflow lifecycle ────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding/{instanceId}/validate")
    public OffboardingWorkflowInstanceDto validateWorkflow(
            @PathVariable Long instanceId,
            Authentication auth) {
        return offboardingService.validateWorkflow(instanceId, actorId(auth));
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/cancel")
    public OffboardingWorkflowInstanceDto cancelWorkflow(
            @PathVariable Long instanceId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        String reason = body != null ? body.get("reason") : null;
        return offboardingService.cancelWorkflow(instanceId, reason, actorId(auth));
    }

    // ── Exit interview ────────────────────────────────────────────────────────

    @PostMapping("/api/hr/offboarding/{instanceId}/exit-interview")
    @ResponseStatus(HttpStatus.CREATED)
    public ExitInterviewDto saveExitInterview(
            @PathVariable Long instanceId,
            @Valid @RequestBody ExitInterviewRequestDto request,
            Authentication auth) {
        return offboardingService.saveExitInterview(instanceId, request, actorId(auth));
    }

    @GetMapping("/api/hr/offboarding/{instanceId}/exit-interview")
    public ExitInterviewDto getExitInterview(@PathVariable Long instanceId) {
        return offboardingService.getExitInterview(instanceId);
    }

    // ── Asset returns ─────────────────────────────────────────────────────────

    @GetMapping("/api/hr/offboarding/{instanceId}/assets")
    public List<OffboardingAssetReturnDto> listAssetReturns(@PathVariable Long instanceId) {
        return offboardingService.listAssetReturns(instanceId);
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public OffboardingAssetReturnDto addAssetReturn(
            @PathVariable Long instanceId,
            @Valid @RequestBody CreateAssetReturnDto dto) {
        dto.setWorkflowInstanceId(instanceId);
        return offboardingService.addAssetReturn(instanceId, dto);
    }

    @PostMapping("/api/hr/offboarding/{instanceId}/assets/sync-from-it")
    public List<OffboardingAssetReturnDto> reseedItAssets(@PathVariable Long instanceId) {
        return offboardingService.reseedItAssets(instanceId);
    }

    @PatchMapping("/api/hr/offboarding/assets/{assetId}/confirm-return")
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
