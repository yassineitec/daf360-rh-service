package com.daf360.rh.controller;

import com.daf360.rh.dto.onboarding.*;
import com.daf360.rh.service.OnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/kpi")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public OnboardingKpiDto getKpi() {
        return onboardingService.getKpi();
    }

    @GetMapping("/pending")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public List<OnboardingListItem> getPendingList() {
        return onboardingService.getPendingList();
    }

    @GetMapping("/{candidateId}/form")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public OnboardingFormResponse getForm(@PathVariable Long candidateId) {
        return onboardingService.getOnboardingForm(candidateId);
    }

    @PostMapping("/{candidateId}/draft")
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public Map<String, Object> saveDraft(
            @PathVariable Long candidateId,
            @RequestBody SaveDraftRequest dto,
            Authentication auth) {
        return onboardingService.saveDraft(candidateId, dto, actorId(auth));
    }

    /**
     * POST /api/hr/onboarding/{candidateId}/contract-document
     *
     * Uploads the signed contract PDF on the Contrat step, which runs BEFORE completion
     * creates the employee profile — so the file is staged against the candidate and returned
     * as {url, name} for the draft to carry. Completion turns it into a document on the
     * profile (documentType CONTRACT_SIGNED).
     */
    @PostMapping(value = "/{candidateId}/contract-document",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public Map<String, String> uploadContractDocument(
            @PathVariable Long candidateId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return onboardingService.stageContractDocument(candidateId, file);
    }

    @PostMapping("/{candidateId}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
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
