package com.daf360.rh.controller;

import com.daf360.rh.dto.contract.*;
import com.daf360.rh.service.ContractHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ContractHistoryController {

    private final ContractHistoryService service;

    // ── TypeContrat reference ─────────────────────────────────────────────────

    @GetMapping("/api/hr/ref/type-contrat")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<TypeContratDto>> getTypeContrats() {
        return ResponseEntity.ok(service.getAllTypeContrats());
    }

    @PostMapping("/api/hr/ref/type-contrat")
    //@PreAuthorize("hasPermission(null,'ADMIN_LISTS')")
    public ResponseEntity<TypeContratDto> createTypeContrat(@RequestBody TypeContratDto req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTypeContrat(req));
    }

    @DeleteMapping("/api/hr/ref/type-contrat/{id}")
    //@PreAuthorize("hasPermission(null,'ADMIN_LISTS')")
    public ResponseEntity<Void> deleteTypeContrat(@PathVariable Long id) {
        service.deleteTypeContrat(id);
        return ResponseEntity.noContent().build();
    }

    // ── Contract history ──────────────────────────────────────────────────────

    @GetMapping("/api/hr/profiles/{profileId}/contrats")
    //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<List<ContractHistoryDto>> getHistory(@PathVariable Long profileId) {
        return ResponseEntity.ok(service.getHistory(profileId));
    }

    @GetMapping("/api/hr/profiles/{profileId}/contrats/actif")
    //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<ContractHistoryDto> getActiveContract(@PathVariable Long profileId) {
        ContractHistoryDto active = service.getActiveContract(profileId);
        if (active == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(active);
    }

    @PostMapping("/api/hr/profiles/{profileId}/contrats")
    //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES')")
    public ResponseEntity<ContractHistoryDto> addContract(
            @PathVariable Long profileId,
            @Valid @RequestBody CreateContractRequest req,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addContract(profileId, req, auth));
    }
}
