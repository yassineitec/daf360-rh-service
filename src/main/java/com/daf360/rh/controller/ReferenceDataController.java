package com.daf360.rh.controller;

import com.daf360.rh.dto.ref.CreateRefDataRequest;
import com.daf360.rh.dto.ref.RefDataItemDto;
import com.daf360.rh.service.ReferenceDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/ref")
@RequiredArgsConstructor
public class ReferenceDataController {

    private final ReferenceDataService refService;

    // ── Grades ────────────────────────────────────────────────────────────────

    @GetMapping("/grades")
    @PreAuthorize("isAuthenticated()")
    public List<RefDataItemDto> getGrades(@RequestParam Long paysId) {
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
    public List<RefDataItemDto> getDisciplines(@RequestParam Long paysId) {
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
    public List<RefDataItemDto> getNogLevels(@RequestParam Long paysId) {
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
    public List<RefDataItemDto> getDepartments(@RequestParam Long paysId) {
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
    public List<RefDataItemDto> getBanks(@RequestParam Long paysId) {
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
}
