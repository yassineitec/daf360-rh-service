package com.daf360.rh.controller;

import com.daf360.rh.dto.dashboard.*;
import com.daf360.rh.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    public ResponseEntity<List<ProbationAlertDto>> getProbation(
            @RequestParam(defaultValue = "30") int joursMax) {
        return ResponseEntity.ok(dashboardService.getProbationAlerts(joursMax));
    }

    @GetMapping("/anniversaires")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AnniversaireDto>> getAnniversaires(
            @RequestParam(required = false) Integer mois) {
        int m = mois != null ? mois : LocalDate.now().getMonthValue();
        return ResponseEntity.ok(dashboardService.getAnniversaires(m));
    }

    @GetMapping("/nouveaux-employes")
    ////@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<NouvelEmployeDto>> getNouveaux(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(dashboardService.getNouveauxEmployes(limit));
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
