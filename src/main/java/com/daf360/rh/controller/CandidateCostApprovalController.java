package com.daf360.rh.controller;

import com.daf360.rh.dto.hiring.CandidateCostApprovalDto;
import com.daf360.rh.dto.hiring.CandidateSimulationSummaryDto;
import com.daf360.rh.dto.hiring.ReviewCostApprovalRequest;
import com.daf360.rh.dto.hiring.SubmitCostApprovalRequest;
import com.daf360.rh.service.CandidateCostApprovalService;
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
    public List<CandidateCostApprovalDto> pending(@RequestParam Long paysId) {
        return service.getPendingByPays(paysId);
    }

    /** Candidates that have at least one simulation, with count + latest status. */
    @GetMapping("/candidates-with-history")
    @PreAuthorize("hasAnyAuthority('RH_HIRE_CANDIDATE','APPROVE_HIRING_COST')")
    public List<CandidateSimulationSummaryDto> candidatesWithHistory(@RequestParam Long paysId) {
        return service.getCandidatesWithHistory(paysId);
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
        return service.approve(id, request.getNotes(), actorId(auth));
    }

    /** CD rejects. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('APPROVE_HIRING_COST')")
    public CandidateCostApprovalDto reject(@PathVariable Long id,
                                           @Valid @RequestBody ReviewCostApprovalRequest request,
                                           Authentication auth) {
        return service.reject(id, request.getNotes(), request.getContrePropSalaire(), actorId(auth));
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
