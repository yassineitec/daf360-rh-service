package com.daf360.rh.controller;

import com.daf360.rh.dto.break_.BreakLegalRuleDto;
import com.daf360.rh.dto.break_.BreakTemplateDto;
import com.daf360.rh.dto.break_.CreateBreakLegalRuleRequest;
import com.daf360.rh.dto.break_.CreateBreakTemplateRequest;
import com.daf360.rh.service.BreakTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class BreakTemplateController {

    private final BreakTemplateService breakService;

    // ── Break templates ───────────────────────────────────────────────────────

    @GetMapping("/api/hr/breaks/templates")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<List<BreakTemplateDto>> getTemplates(
            @RequestParam(required = false) Long regimeId,
            @RequestParam(required = false) Long paysId) {
        List<BreakTemplateDto> result = (regimeId != null)
                ? breakService.getTemplatesForRegime(regimeId)
                : breakService.getTemplatesForPays(paysId != null ? paysId : 0L);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/hr/breaks/templates")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<BreakTemplateDto> createTemplate(
            @RequestBody CreateBreakTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(breakService.createTemplate(req));
    }

    @DeleteMapping("/api/hr/breaks/templates/{id}")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        breakService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    // ── Legal rules ───────────────────────────────────────────────────────────

    @GetMapping("/api/hr/breaks/legal-rules")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<List<BreakLegalRuleDto>> getLegalRules(@RequestParam Long paysId) {
        return ResponseEntity.ok(breakService.getLegalRules(paysId));
    }

    @PostMapping("/api/hr/breaks/legal-rules")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<BreakLegalRuleDto> createLegalRule(
            @RequestBody CreateBreakLegalRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(breakService.createLegalRule(req));
    }

    @DeleteMapping("/api/hr/breaks/legal-rules/{id}")
    @PreAuthorize("hasPermission(null,'ADMIN_BREAKS')")
    public ResponseEntity<Void> deleteLegalRule(@PathVariable Long id) {
        breakService.deleteLegalRule(id);
        return ResponseEntity.noContent().build();
    }
}
