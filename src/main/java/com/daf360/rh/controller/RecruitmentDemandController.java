package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.RecruitmentDemandStatus;
import com.daf360.rh.dto.recruitment.*;
import com.daf360.rh.service.RecruitmentDemandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/hr/recruitment-demands")
@RequiredArgsConstructor
public class RecruitmentDemandController {

    private final RecruitmentDemandService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse create(@Valid @RequestBody CreateRecruitmentDemandRequest request,
                                             Authentication auth) {
        return service.create(request, actorId(auth));
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'RH_VIEW_RECRUITMENT_DEMAND')")
    public Page<RecruitmentDemandSummary> listByPays(
            @RequestParam Long paysId,
            @RequestParam(required = false) RecruitmentDemandStatus statut,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.listByPays(paysId, statut, pageable);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND')")
    public Page<RecruitmentDemandSummary> listMine(
            @RequestParam(required = false) RecruitmentDemandStatus statut,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication auth) {
        return service.listMine(actorId(auth), statut, pageable);
    }

    @GetMapping("/options")
    public List<ApprovedDemandOption> approvedOptions(@RequestParam Long paysId) {
        return service.getApprovedOptions(paysId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'RH_VIEW_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse review(@PathVariable Long id,
                                             @Valid @RequestBody ReviewRecruitmentDemandRequest request,
                                             Authentication auth) {
        return service.review(id, request, actorId(auth));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse cancel(@PathVariable Long id, Authentication auth) {
        return service.cancel(id, actorId(auth));
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
