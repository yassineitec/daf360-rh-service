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

    /**
     * « Bibliothèque des postes » (self-service): past approved / closed positions of the entity,
     * one per post, with the values to raise the same demand again. Same gate as creating one,
     * since that is all it is for.
     */
    @GetMapping("/library")
    @PreAuthorize("hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND')")
    public List<JobLibraryEntry> library(@RequestParam Long paysId) {
        return service.library(paysId);
    }

    @GetMapping("/options")
    public List<ApprovedDemandOption> approvedOptions(@RequestParam Long paysId) {
        return service.getApprovedOptions(paysId);
    }

    /**
     * GET /{id}/related-archived-candidates — archived candidatures of the other demands raised
     * for the same post: the candidate creation wizard offers to start from one of them.
     * Gated like candidate creation, since that is the only screen that reads it.
     */
    @GetMapping("/{id}/related-archived-candidates")
    @PreAuthorize("hasPermission(null, 'CREATE_CANDIDATE') or hasPermission(null, 'EDIT_CANDIDATE')")
    public List<com.daf360.rh.dto.pipeline.ArchivedCandidateDto> relatedArchivedCandidates(@PathVariable Long id) {
        return service.relatedArchivedCandidates(id);
    }

    /**
     * GET /{id}/candidates — the candidatures of the demand, for its detail page. Gated like
     * the demand page itself (a manager who raised it may follow who applies), and light:
     * no salary, nothing from the candidate's file.
     */
    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasPermission(null, 'RH_VIEW_RECRUITMENT_DEMAND') or hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND') "
            + "or hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND')")
    public List<DemandCandidateDto> candidates(@PathVariable Long id) {
        return service.candidatesOf(id);
    }

    /** GET /{id}/history — the demand's « Historique » (drawer of its detail page). Gated like candidates above. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasPermission(null, 'RH_VIEW_RECRUITMENT_DEMAND') or hasPermission(null, 'RH_CREATE_RECRUITMENT_DEMAND') "
            + "or hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND')")
    public List<DemandHistoryEvent> history(@PathVariable Long id) {
        return service.history(id);
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

    // ── Posts filled (V137) ──────────────────────────────────────────────────

    /** Body of close: an optional note, shown as the archive reason of its open candidatures. */
    public record CloseDemandRequest(String note) {}

    /** Body of headcount: the new number of posts. */
    public record HeadcountRequest(Integer headcount) {}

    /**
     * Closes an approved demand by hand (position cancelled, budget frozen…). The candidatures
     * still in recruitment are archived. The recruitment team runs the vacancy, so they may
     * close it as well as the approvers.
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND') or hasPermission(null, 'CREATE_CANDIDATE')")
    public RecruitmentDemandResponse close(@PathVariable Long id,
                                           @RequestBody(required = false) CloseDemandRequest body,
                                           Authentication auth) {
        return service.close(id, body != null ? body.note() : null, actorId(auth));
    }

    /** Reopens a closed demand — an approver's call, since it restarts the recruitment. */
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse reopen(@PathVariable Long id, Authentication auth) {
        return service.reopen(id, actorId(auth));
    }

    /**
     * Changes the number of posts. An approver's call: raising it is approving more hires than
     * were validated.
     */
    @PutMapping("/{id}/headcount")
    @PreAuthorize("hasPermission(null, 'RH_APPROVE_RECRUITMENT_DEMAND')")
    public RecruitmentDemandResponse changeHeadcount(@PathVariable Long id,
                                                     @RequestBody HeadcountRequest body,
                                                     Authentication auth) {
        if (body == null || body.headcount() == null) {
            throw new com.daf360.rh.exception.AppException(
                    com.daf360.rh.exception.ErrorCode.BUSINESS_RULE_VIOLATION, "Le nombre de postes est obligatoire.");
        }
        return service.changeHeadcount(id, body.headcount(), actorId(auth));
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
