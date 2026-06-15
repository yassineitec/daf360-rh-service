package com.daf360.rh.controller;

import com.daf360.rh.dto.pipeline.KanbanCandidateDto;
import com.daf360.rh.dto.pipeline.KanbanColumnDto;
import com.daf360.rh.dto.pipeline.PipelineStatsDto;
import com.daf360.rh.service.PipelineService;
import lombok.RequiredArgsConstructor;
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
    public ResponseEntity<PipelineStatsDto> getStats() {
        return ResponseEntity.ok(pipelineService.getStats());
    }

    /**
     * GET /api/hr/pipeline/kanban
     * All pipeline stages with their candidates, grouped for Kanban display.
     * Stages: SCREENING, ENTRETIEN, OFFRE, RECRUTE, REJETE
     */
    @GetMapping("/kanban")
    public ResponseEntity<List<KanbanColumnDto>> getKanban() {
        return ResponseEntity.ok(pipelineService.getKanban());
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
            @PageableDefault(size = 15) Pageable pageable) {

        return ResponseEntity.ok(pipelineService.getCandidatesPaged(stage, search, pageable));
    }

    /**
     * PUT /api/hr/pipeline/candidates/{id}/stage?stage=OFFRE
     * Move a candidate to a different pipeline stage.
     * stage: SCREENING | ENTRETIEN | OFFRE | RECRUTE | REJETE
     */
    @PutMapping("/candidates/{id}/stage")
    public ResponseEntity<KanbanCandidateDto> moveToStage(
            @PathVariable Long id,
            @RequestBody String stage) {

        return ResponseEntity.ok(pipelineService.moveToStage(id, stage));
    }
}
