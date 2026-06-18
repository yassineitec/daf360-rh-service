package com.daf360.rh.controller;

import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.lifecycle.EmployeeLifecycleService;
import com.daf360.rh.lifecycle.LifecycleAlertJob;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class EmployeeLifecycleController {

    private final EmployeeLifecycleService lifecycleService;
    private final LifecycleAlertJob        alertJob;

    // ── Employee contracts ────────────────────────────────────────────────────

    @GetMapping("/api/hr/employees/{id}/contracts")
    public List<ContractListDto> getContracts(@PathVariable Long id) {
        return lifecycleService.getContractsForEmployee(id);
    }

    @PostMapping("/api/hr/employees/{id}/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractDetailDto createContract(
            @PathVariable Long id,
            @Valid @RequestBody CreateContractRequest dto,
            Authentication auth) {
        dto.setEmployeeProfileId(id);
        return lifecycleService.createContract(dto, actorId(auth));
    }

    @GetMapping("/api/hr/employees/{id}/lifecycle-history")
    public List<ContractTransitionHistoryDto> getLifecycleHistory(@PathVariable Long id) {
        return lifecycleService.getEmployeeLifecycleHistory(id);
    }

    // ── Single contract operations ────────────────────────────────────────────

    @GetMapping("/api/hr/contracts/{id}")
    public ContractDetailDto getContract(@PathVariable Long id) {
        return lifecycleService.getContract(id);
    }

    @PostMapping("/api/hr/contracts/{id}/transition")
    public ContractDetailDto transition(
            @PathVariable Long id,
            @Valid @RequestBody TransitionRequest dto,
            Authentication auth) {
        return lifecycleService.transitionState(id, dto, actorId(auth));
    }

    @PostMapping("/api/hr/contracts/{id}/validate-trial")
    public ContractDetailDto validateTrial(
            @PathVariable Long id,
            @Valid @RequestBody ValidateTrialRequest dto,
            Authentication auth) {
        return lifecycleService.validateTrialPeriod(id, dto, actorId(auth));
    }

    @PostMapping("/api/hr/contracts/{id}/renew-cdd")
    public ContractDetailDto renewCDD(
            @PathVariable Long id,
            @Valid @RequestBody RenewCDDRequest dto,
            Authentication auth) {
        return lifecycleService.renewCDD(id, dto, actorId(auth));
    }

    @PostMapping("/api/hr/contracts/{id}/convert-to-cdi")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractDetailDto convertToCDI(
            @PathVariable Long id,
            @Valid @RequestBody ConvertToCDIRequest dto,
            Authentication auth) {
        return lifecycleService.convertToCDI(id, dto, actorId(auth));
    }

    @GetMapping("/api/hr/contracts/{id}/history")
    public List<ContractTransitionHistoryDto> getContractHistory(@PathVariable Long id) {
        return lifecycleService.getContractHistory(id);
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @GetMapping("/api/hr/lifecycle/alerts")
    public List<LifecycleAlertDto> getAlerts(
            @RequestParam Long paysId,
            @RequestParam(required = false) Boolean acknowledged) {
        return lifecycleService.getAlerts(paysId, acknowledged);
    }

    @PostMapping("/api/hr/lifecycle/alerts/{id}/acknowledge")
    public LifecycleAlertDto acknowledgeAlert(
            @PathVariable Long id,
            Authentication auth) {
        return lifecycleService.acknowledgeAlert(id, actorId(auth));
    }

    @PostMapping("/api/hr/lifecycle/alerts/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void triggerAlertJob() {
        alertJob.processLifecycleAlerts();
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @GetMapping("/api/hr/lifecycle/config")
    public ContractTypeConfigDto getConfig(
            @RequestParam Long paysId,
            @RequestParam String contractTypeCode) {
        return lifecycleService.getConfig(paysId, contractTypeCode);
    }

    @PatchMapping("/api/hr/lifecycle/config/{id}")
    public ContractTypeConfigDto updateConfig(
            @PathVariable Long id,
            @RequestBody UpdateContractTypeConfigRequest dto,
            Authentication auth) {
        return lifecycleService.updateConfig(id, dto, actorId(auth));
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
