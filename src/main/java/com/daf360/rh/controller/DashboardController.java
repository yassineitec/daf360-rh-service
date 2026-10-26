package com.daf360.rh.controller;

import com.daf360.rh.common.PreviewResponse;
import com.daf360.rh.dto.dashboard.*;
import com.daf360.rh.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<DashboardStatsDto> getStats(
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getStats(paysId));
    }

    @GetMapping("/workforce")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkforceStatsDto> getWorkforce(
            @RequestParam(required = false) Long paysId,
            @RequestParam(required = false) Long departmentId) {
        return ResponseEntity.ok(dashboardService.getWorkforceStats(paysId, departmentId));
    }

    @GetMapping("/completion")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProfileCompletionDto> getCompletion(
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getCompletion(paysId));
    }

    @GetMapping("/fin-periode-essai")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PreviewResponse<ProbationAlertDto>> getProbation(
            @RequestParam(defaultValue = "30") int joursMax,
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getProbationAlerts(joursMax, paysId));
    }

    @GetMapping("/anniversaires")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AnniversaireDto>> getAnniversaires(
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getAnniversaires(0, paysId));
    }

    @GetMapping("/nouveaux-employes")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NouvelEmployeDto>> getNouveaux(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getNouveauxEmployes(limit, paysId));
    }

    @GetMapping("/missing-documents")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PreviewResponse<MissingDocumentDto>> getMissingDocuments(
            @RequestParam(required = false) Long paysId) {
        return ResponseEntity.ok(dashboardService.getMissingDocuments(paysId));
    }

    // ── Page 1: Tableau de bord RH ────────────────────────────────────────────

    @GetMapping("/weekly-stats")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<WeeklyStatsDto> getWeeklyStats() {
        return ResponseEntity.ok(dashboardService.getWeeklyStats());
    }

    @GetMapping("/recruitment-stats")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<RecruitmentStatsDto> getRecruitmentStats() {
        return ResponseEntity.ok(dashboardService.getRecruitmentStats());
    }

    @GetMapping("/recent-activity")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RecentActivityDto>> getRecentActivity(
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(dashboardService.getRecentActivity(size));
    }
}
