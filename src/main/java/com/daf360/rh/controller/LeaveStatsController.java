package com.daf360.rh.controller;

import com.daf360.rh.export.XlsxExport;
import com.daf360.rh.service.LeaveStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * « Statistiques des absences » (/rh/absences/statistiques): approved congé and maladie days
 * per employee and month, with KPI totals, filter choices and an Excel export — the page that
 * used to live in the timesheet as « Statistiques RH ». Gated by GET_HR_STATS, the same code
 * the timesheet checked; country-scoped by the caller's role (LeaveStatsService).
 *
 * List, choices and export take the same filters, so the file holds what the list shows.
 */
@RestController
@RequestMapping("/api/hr/leave/stats")
@RequiredArgsConstructor
public class LeaveStatsController {

    private final LeaveStatsService service;

    @GetMapping
    @PreAuthorize("hasAuthority('GET_HR_STATS')")
    public Map<String, Object> page(@RequestParam(required = false) Long paysId,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) List<Long> collaborateurs,
                                    @RequestParam(required = false) List<Integer> months,
                                    @RequestParam(required = false) List<Integer> years,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "asc") String dir,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        LeaveStatsService.Result result = service.page(
                new LeaveStatsService.Filters(paysId, search, collaborateurs, months, years), sort, dir, page, size);
        return Map.of(
                "content", result.page(),
                "totalElements", result.totalElements(),
                "page", page,
                "size", size,
                "summary", result.summary());
    }

    @GetMapping("/filter-options")
    @PreAuthorize("hasAuthority('GET_HR_STATS')")
    public LeaveStatsService.FilterOptions filterOptions(@RequestParam(required = false) Long paysId) {
        return service.filterOptions(paysId);
    }

    /** One sheet, one row per employee and month, in the list's order (capped like every RH export). */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('GET_HR_STATS')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long paysId,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) List<Long> collaborateurs,
                                         @RequestParam(required = false) List<Integer> months,
                                         @RequestParam(required = false) List<Integer> years,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(defaultValue = "asc") String dir,
                                         @RequestParam(defaultValue = "fr") String lang) {
        boolean fr = !"en".equalsIgnoreCase(lang);
        Locale locale = fr ? Locale.FRENCH : Locale.ENGLISH;
        List<LeaveStatsService.StatRow> rows = service.all(
                new LeaveStatsService.Filters(paysId, search, collaborateurs, months, years), sort, dir);
        return XlsxExport.response("statistiques-absences.xlsx", fr ? "Statistiques" : "Statistics",
                fr ? List.of("Collaborateur", "Mois", "Année", "Jours de congé", "Jours de maladie", "Total")
                   : List.of("Employee", "Month", "Year", "Leave days", "Sick days", "Total"),
                rows.stream().limit(XlsxExport.MAX_ROWS).map(r -> Arrays.<Object>asList(
                        r.collaborateurName(),
                        capitalised(Month.of(r.month()).getDisplayName(TextStyle.FULL, locale)),
                        r.year(), r.totalConge(), r.totalMaladie(), r.total())).toList());
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
