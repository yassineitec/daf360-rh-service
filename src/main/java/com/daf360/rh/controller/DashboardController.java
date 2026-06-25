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
    public ResponseEntity<DashboardStatsDto> getStats() {
        return ResponseEntity.ok(dashboardService.getStats());
    }

    @GetMapping("/workforce")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<WorkforceStatsDto> getWorkforce() {
        return ResponseEntity.ok(dashboardService.getWorkforceStats());
    }

    @GetMapping("/completion")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProfileCompletionDto> getCompletion() {
        return ResponseEntity.ok(dashboardService.getCompletion());
    }

    @GetMapping("/fin-periode-essai")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PreviewResponse<ProbationAlertDto>> getProbation(
            @RequestParam(defaultValue = "30") int joursMax) {
        return ResponseEntity.ok(dashboardService.getProbationAlerts(joursMax));
    }

    @GetMapping("/anniversaires")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AnniversaireDto>> getAnniversaires() {
        return ResponseEntity.ok(dashboardService.getAnniversaires(0));
    }

    @GetMapping("/nouveaux-employes")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NouvelEmployeDto>> getNouveaux(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(dashboardService.getNouveauxEmployes(limit));
    }

    @GetMapping("/missing-documents")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PreviewResponse<MissingDocumentDto>> getMissingDocuments() {
        return ResponseEntity.ok(dashboardService.getMissingDocuments());
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
