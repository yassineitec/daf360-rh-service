package com.daf360.rh.controller;

import com.daf360.rh.dto.pipeline.KanbanCandidateDto;
import com.daf360.rh.dto.pipeline.KanbanColumnDto;
import com.daf360.rh.dto.pipeline.PipelineActivityDto;
import com.daf360.rh.dto.pipeline.PipelineObjectiveDto;
import com.daf360.rh.dto.pipeline.PipelineStatsDto;
import com.daf360.rh.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /**
     * GET /api/hr/pipeline/stats
     * Global pipeline statistics: active candidates, average time-in-pipeline, urgent positions.
     */
    @GetMapping("/stats")
    public ResponseEntity<PipelineStatsDto> getStats(
            @RequestParam(required = false) List<Long> paysId) {
        return ResponseEntity.ok(pipelineService.getStats(paysId));
    }

    /**
     * GET /api/hr/pipeline/kanban
     * All pipeline stages with their candidates, grouped for Kanban display.
     * Stages: SCREENING, ENTRETIEN, OFFRE, RECRUTE, REJETE
     */
    @GetMapping("/kanban")
    public ResponseEntity<List<KanbanColumnDto>> getKanban(
            @RequestParam(required = false) List<Long> paysId) {
        return ResponseEntity.ok(pipelineService.getKanban(paysId));
    }

    /**
     * GET /api/hr/pipeline/candidates?page=0&size=15&stage=ENTRETIEN&search=alice
     * Paginated candidate list, optionally filtered by pipeline stage and/or search text.
     * stage: SCREENING | ENTRETIEN | OFFRE | RECRUTE | REJETE (omit for all active)
     */
    @GetMapping("/candidates")
    public ResponseEntity<Page<KanbanCandidateDto>> getCandidates(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<Long> paysId,
            @PageableDefault(size = 15) Pageable pageable) {

        return ResponseEntity.ok(pipelineService.getCandidatesPaged(stage, search, paysId, pageable));
    }

    /**
     * PUT /api/hr/pipeline/candidates/{id}/stage?stage=OFFRE
     * Move a candidate to a different pipeline stage.
     * stage: SCREENING | ENTRETIEN | OFFRE | RECRUTE | REJETE
     */
    @PutMapping("/candidates/{id}/stage")
    @PreAuthorize("hasAnyAuthority('EDIT_CANDIDATE','HR_ONBOARDING')")
    public ResponseEntity<KanbanCandidateDto> moveToStage(
            @PathVariable Long id,
            @RequestBody String stage,
            org.springframework.security.core.Authentication auth) {

        return ResponseEntity.ok(pipelineService.moveToStage(id, stage, actorId(auth)));
    }

    /**
     * GET /api/hr/pipeline/archived
     * Every ARCHIVED candidature with why it was archived (V137) — the « Archivés » column of
     * the Pipeline RH board. Same read gate as the board's « Candidat » column.
     */
    @GetMapping("/archived")
    @PreAuthorize("hasAnyAuthority('VIEW_CANDIDATES','EDIT_CANDIDATE','CREATE_CANDIDATE','HR_ONBOARDING')")
    public ResponseEntity<List<com.daf360.rh.dto.pipeline.ArchivedCandidateDto>> getArchived() {
        return ResponseEntity.ok(pipelineService.getArchived());
    }

    private static Long actorId(org.springframework.security.core.Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * GET /api/hr/pipeline/activity
     * 20 most recent stage-change events sourced from the audit_log table.
     */
    @GetMapping("/activity")
    public ResponseEntity<List<PipelineActivityDto>> getActivity(
            @RequestParam(required = false) List<Long> paysId) {
        return ResponseEntity.ok(pipelineService.getRecentActivity(paysId));
    }

    /**
     * GET /api/hr/pipeline/objectives
     * Monthly recruitment targets (from recruitment_demands.headcount) vs
     * actuals (HIRED candidates) for the last 6 months.
     */
    @GetMapping("/objectives")
    public ResponseEntity<List<PipelineObjectiveDto>> getObjectives(
            @RequestParam(required = false) List<Long> paysId) {
        return ResponseEntity.ok(pipelineService.getObjectives(paysId));
    }
}
