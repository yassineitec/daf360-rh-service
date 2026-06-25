package com.daf360.rh.controller;

import com.daf360.rh.dto.onboarding.*;
import com.daf360.rh.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/kpi")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public OnboardingKpiDto getKpi() {
        return onboardingService.getKpi();
    }

    @GetMapping("/pending")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public List<OnboardingListItem> getPendingList() {
        return onboardingService.getPendingList();
    }

    @GetMapping("/{candidateId}/form")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public OnboardingFormResponse getForm(@PathVariable Long candidateId) {
        return onboardingService.getOnboardingForm(candidateId);
    }

    @PostMapping("/{candidateId}/draft")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public Map<String, Object> saveDraft(
            @PathVariable Long candidateId,
            @RequestBody SaveDraftRequest dto,
            Authentication auth) {
        return onboardingService.saveDraft(candidateId, dto, actorId(auth));
    }

    @PostMapping("/{candidateId}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CompletionResult completeProfile(
            @PathVariable Long candidateId,
            @Valid @RequestBody CompleteProfileRequest dto,
            Authentication auth) {
        return onboardingService.completeEmployeeProfile(candidateId, dto, actorId(auth));
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try { return Long.valueOf(auth.getPrincipal().toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
