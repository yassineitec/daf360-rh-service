package com.daf360.rh.controller;

import com.daf360.rh.dto.hiring.CandidateCostApprovalDto;
import com.daf360.rh.dto.hiring.CandidateSimulationSummaryDto;
import com.daf360.rh.dto.hiring.ReviewCostApprovalRequest;
import com.daf360.rh.dto.hiring.SubmitCostApprovalRequest;
import com.daf360.rh.service.CandidateCostApprovalService;
import com.daf360.rh.service.OfferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/cost-approvals")
@RequiredArgsConstructor
public class CandidateCostApprovalController {

    private final CandidateCostApprovalService service;
    /** Decisions on an offer round move the round too (V133) — the offer service owns that. */
    private final OfferService offerService;
    private final com.daf360.rh.security.TenantService tenantService;

    /** HR officer submits a cost simulation for CD review. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RH_HIRE_CANDIDATE')")
    public CandidateCostApprovalDto submit(@Valid @RequestBody SubmitCostApprovalRequest request,
                                           Authentication auth) {
        return service.submit(request, actorId(auth));
    }

    /** CD inbox — pending approvals for a specific country. */
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('APPROVE_HIRING_COST')")
    public List<CandidateCostApprovalDto> pending(@RequestParam(required = false) List<Long> paysId) {
        // Drapeaux de la barre (`paysId=179,64`, ids RH), bornés au périmètre du rôle — le
        // pays demandé n'était pas contrôlé jusqu'ici.
        return service.getPendingByPaysIn(tenantService.resolvePaysList(paysId));
    }

    /** Candidates that have at least one simulation, with count + latest status. */
    @GetMapping("/candidates-with-history")
    @PreAuthorize("hasAnyAuthority('RH_HIRE_CANDIDATE','APPROVE_HIRING_COST')")
    public List<CandidateSimulationSummaryDto> candidatesWithHistory(@RequestParam(required = false) List<Long> paysId) {
        // Drapeaux de la barre (`paysId=179,53`), bornés au périmètre du rôle.
        return service.getCandidatesWithHistoryIn(tenantService.resolvePaysList(paysId));
    }

    /** All approval records for a candidate (used on the candidate detail page). */
    @GetMapping("/candidate/{candidateId}")
    @PreAuthorize("hasAnyAuthority('RH_HIRE_CANDIDATE','APPROVE_HIRING_COST')")
    public List<CandidateCostApprovalDto> byCandidate(@PathVariable Long candidateId) {
        return service.getByCandidate(candidateId);
    }

    /** CD approves. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('APPROVE_HIRING_COST')")
    public CandidateCostApprovalDto approve(@PathVariable Long id,
                                            @Valid @RequestBody ReviewCostApprovalRequest request,
                                            Authentication auth) {
        return offerService.decideByFinance(id, true, request.getNotes(), null, null, actorId(auth));
    }

    /**
     * CD rejects — optionally naming its own figure, which on an offer round goes straight to
     * the candidate as the next round (see {@link OfferService#decideByFinance}).
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('APPROVE_HIRING_COST')")
    public CandidateCostApprovalDto reject(@PathVariable Long id,
                                           @Valid @RequestBody ReviewCostApprovalRequest request,
                                           Authentication auth) {
        return offerService.decideByFinance(id, false, request.getNotes(), request.getContrePropSalaire(),
                request.getContrePropSimulationSnapshot(), actorId(auth));
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return 1L;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
