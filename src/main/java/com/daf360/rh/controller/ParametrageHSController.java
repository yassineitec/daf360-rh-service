package com.daf360.rh.controller;

import com.daf360.rh.dto.overtime.*;
import com.daf360.rh.service.ParametrageHSService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ParametrageHSController {

    private final ParametrageHSService service;

    // ── Config CRUD ───────────────────────────────────────────────────────────

    @GetMapping("/api/hr/config/hs")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ParametrageHSDto>> getAll() {
        return ResponseEntity.ok(service.getAllActive());
    }

    @GetMapping("/api/hr/config/hs/pays/{paysId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ParametrageHSDto>> getForPays(@PathVariable Long paysId) {
        return ResponseEntity.ok(service.getForPays(paysId));
    }

    @GetMapping("/api/hr/config/hs/pays/{paysId}/active")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ParametrageHSDto> getActive(@PathVariable Long paysId) {
        ParametrageHSDto dto = service.getActive(paysId);
        if (dto == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/api/hr/config/hs")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES','ADMIN_ROLES','GET_PAYS')")
    public ResponseEntity<ParametrageHSDto> create(
            @Valid @RequestBody CreateParametrageHSRequest req,
            Authentication auth) {
        Long actorId = auth != null && auth.getPrincipal() != null
                ? tryParseLong(auth.getPrincipal().toString()) : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, actorId));
    }

    @PutMapping("/api/hr/config/hs/{id}")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES','ADMIN_ROLES','GET_PAYS')")
    public ResponseEntity<ParametrageHSDto> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateParametrageHSRequest req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @DeleteMapping("/api/hr/config/hs/{id}")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES','ADMIN_ROLES','GET_PAYS')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    // ── Reference data ───────────────────────────────────────────────────────

    @GetMapping("/api/hr/config/hs/pays-list")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Map<String, Object>>> getAllPays() {
        return ResponseEntity.ok(service.getAllPays());
    }

    // ── Calculation ───────────────────────────────────────────────────────────

    @PostMapping("/api/hr/config/hs/calculate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OvertimeCalculationResult> calculate(
            @Valid @RequestBody OvertimeCalculationRequest req) {
        return ResponseEntity.ok(service.calculate(req));
    }

    private Long tryParseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
