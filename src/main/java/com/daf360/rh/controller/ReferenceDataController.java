package com.daf360.rh.controller;

import com.daf360.rh.dto.ref.CreateRefDataRequest;
import com.daf360.rh.dto.ref.RefDataItemDto;
import com.daf360.rh.service.ReferenceDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/ref")
@RequiredArgsConstructor
public class ReferenceDataController {

    private final ReferenceDataService refService;
    private final JdbcTemplate         jdbc;

    // ── Grades ────────────────────────────────────────────────────────────────

    @GetMapping("/grades")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getGrades(@RequestParam(required = false) Long paysId) {
        return refService.getGrades(paysId);
    }

    @PostMapping("/grades")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createGrade(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createGrade(req));
    }

    @DeleteMapping("/grades/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteGrade(@PathVariable Long id) {
        refService.deleteGrade(id);
        return ResponseEntity.noContent().build();
    }

    // ── Disciplines ───────────────────────────────────────────────────────────

    @GetMapping("/disciplines")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getDisciplines(@RequestParam(required = false) Long paysId) {
        return refService.getDisciplines(paysId);
    }

    @PostMapping("/disciplines")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createDiscipline(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createDiscipline(req));
    }

    @DeleteMapping("/disciplines/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteDiscipline(@PathVariable Long id) {
        refService.deleteDiscipline(id);
        return ResponseEntity.noContent().build();
    }

    // ── NOG Levels ────────────────────────────────────────────────────────────

    @GetMapping("/nog-levels")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getNogLevels(@RequestParam(required = false) Long paysId) {
        return refService.getNogLevels(paysId);
    }

    @PostMapping("/nog-levels")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createNogLevel(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createNogLevel(req));
    }

    @DeleteMapping("/nog-levels/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteNogLevel(@PathVariable Long id) {
        refService.deleteNogLevel(id);
        return ResponseEntity.noContent().build();
    }

    // ── Departments ───────────────────────────────────────────────────────────

    @GetMapping("/departments")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getDepartments(@RequestParam(required = false) Long paysId) {
        return refService.getDepartments(paysId);
    }

    @PostMapping("/departments")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createDepartment(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createDepartment(req));
    }

    @DeleteMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteDepartment(@PathVariable Long id) {
        refService.deleteDepartment(id);
        return ResponseEntity.noContent().build();
    }

    // ── Banks ─────────────────────────────────────────────────────────────────

    @GetMapping("/banks")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getBanks(@RequestParam(required = false) Long paysId) {
        return refService.getBanks(paysId);
    }

    @PostMapping("/banks")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createBank(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createBank(req));
    }

    @DeleteMapping("/banks/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteBank(@PathVariable Long id) {
        refService.deleteBank(id);
        return ResponseEntity.noContent().build();
    }

    // ── Nationalities (global) ────────────────────────────────────────────────

    @GetMapping("/nationalities")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getNationalities() {
        return refService.getNationalities();
    }

    @PostMapping("/nationalities")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createNationality(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createNationality(req));
    }

    @DeleteMapping("/nationalities/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteNationality(@PathVariable Long id) {
        refService.deleteNationality(id);
        return ResponseEntity.noContent().build();
    }

    // ── IT Asset Types (read-only — seeded via V23) ───────────────────────────

    @GetMapping("/it-asset-types")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getItAssetTypes() {
        return refService.getItAssetTypes();
    }

    @PostMapping("/it-asset-types")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<RefDataItemDto> createItAssetType(@RequestBody CreateRefDataRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refService.createItAssetType(req));
    }

    @DeleteMapping("/it-asset-types/{id}")
    @PreAuthorize("hasAuthority('ADMIN_LISTS')")
    public ResponseEntity<Void> deleteItAssetType(@PathVariable Long id) {
        refService.deleteItAssetType(id);
        return ResponseEntity.noContent().build();
    }

    // ── Diagnostic (remove after debug) ──────────────────────────────────────

    @GetMapping("/debug")
    public Map<String, Object> debug(@RequestParam(defaultValue = "178") Long paysId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Does this pays exist?
        result.put("pays_" + paysId, jdbc.queryForList(
                "SELECT id, french_label, iso_code, deleted FROM [dbo].[pays] WHERE id = ?", paysId));

        // 2. All active pays in the table
        result.put("all_active_pays", jdbc.queryForList(
                "SELECT id, french_label, iso_code FROM [dbo].[pays] WHERE deleted = 0 ORDER BY id"));

        // 3. Row counts per table for this paysId
        result.put("grades_count",      jdbc.queryForObject("SELECT COUNT(*) FROM [dbo].[grades]      WHERE pays_id = ? AND is_active = 1", Long.class, paysId));
        result.put("disciplines_count", jdbc.queryForObject("SELECT COUNT(*) FROM [dbo].[disciplines]  WHERE pays_id = ? AND is_active = 1", Long.class, paysId));
        result.put("departments_count", jdbc.queryForObject("SELECT COUNT(*) FROM [dbo].[departments]  WHERE pays_id = ? AND is_active = 1", Long.class, paysId));
        result.put("nationalities_count", jdbc.queryForObject("SELECT COUNT(*) FROM [dbo].[nationalities] WHERE is_active = 1", Long.class));

        // 4. Which paysIds actually have data
        result.put("grades_pays_ids",      jdbc.queryForList("SELECT DISTINCT pays_id FROM [dbo].[grades]      ORDER BY pays_id", Long.class));
        result.put("disciplines_pays_ids", jdbc.queryForList("SELECT DISTINCT pays_id FROM [dbo].[disciplines]  ORDER BY pays_id", Long.class));
        result.put("departments_pays_ids", jdbc.queryForList("SELECT DISTINCT pays_id FROM [dbo].[departments]  ORDER BY pays_id", Long.class));

        // 5. Flyway migration status for V23
        result.put("flyway_v23", jdbc.queryForList(
                "SELECT version, description, success, installed_on FROM [dbo].[flyway_schema_history] WHERE version LIKE '23%' ORDER BY installed_rank"));

        return result;
    }
}
