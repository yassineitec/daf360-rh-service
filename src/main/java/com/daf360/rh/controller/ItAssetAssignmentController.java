package com.daf360.rh.controller;

import com.daf360.rh.dto.asset.CreateAssetAssignmentRequest;
import com.daf360.rh.dto.asset.ItAssetAssignmentDto;
import com.daf360.rh.dto.asset.ReturnAssetAssignmentRequest;
import com.daf360.rh.dto.asset.UpdateAssetAssignmentRequest;
import com.daf360.rh.service.ItAssetAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * IT equipment ledger — the "Matériel IT" tab of /rh/profiles/:id.
 *
 * READ reuses the authorities of the Contrats tab rather than a new
 * RH_VIEW_IT_ASSETS code: `daf360_access` is close to the 4096-byte cookie limit for
 * the widest roles (see fix_orphan_permissions.sql), so a code is only worth adding
 * when it gates something the existing ones do not already imply.
 *
 * WRITE takes RH_MANAGE_IT_ASSETS, or IT_PROVISIONING — the IT team hands the
 * hardware over, and it is the same act whether it happens on hire day or two years in.
 */
@RestController
@RequiredArgsConstructor
public class ItAssetAssignmentController {

    private static final String READ_AUTH =
            "hasAnyAuthority('HR_UPDATE_PROFILE','HR_CREATE_PROFILE','HR_ADMIN_ROLES','ADMIN_ROLES','IT_PROVISIONING','RH_MANAGE_IT_ASSETS')";
    private static final String WRITE_AUTH =
            "hasAnyAuthority('RH_MANAGE_IT_ASSETS','IT_PROVISIONING','HR_ADMIN_ROLES','ADMIN_ROLES')";

    private final ItAssetAssignmentService service;

    /** Full history, newest assignment first — items still held and items returned. */
    @GetMapping("/api/hr/profiles/{profileId}/it-assets")
    @PreAuthorize(READ_AUTH)
    public ResponseEntity<List<ItAssetAssignmentDto>> getHistory(@PathVariable Long profileId) {
        return ResponseEntity.ok(service.getHistory(profileId));
    }

    @PostMapping("/api/hr/profiles/{profileId}/it-assets")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<ItAssetAssignmentDto> assign(
            @PathVariable Long profileId,
            @Valid @RequestBody CreateAssetAssignmentRequest req,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.assign(profileId, req, actorId(auth)));
    }

    /**
     * Copies anything marked `provided` on the employee's IT provisioning form that is not
     * in the ledger yet. Idempotent, and the escape hatch for profiles created before V76
     * or before their provisioning was completed.
     */
    @PostMapping("/api/hr/profiles/{profileId}/it-assets/sync-from-provisioning")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<List<ItAssetAssignmentDto>> sync(@PathVariable Long profileId,
                                                           Authentication auth) {
        return ResponseEntity.ok(service.syncFromProvisioning(profileId, actorId(auth)));
    }

    /** Corrects a line. The return is its own endpoint — see below. */
    @PatchMapping("/api/hr/it-assets/{id}")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<ItAssetAssignmentDto> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAssetAssignmentRequest req,
            Authentication auth) {
        return ResponseEntity.ok(service.update(id, req, actorId(auth)));
    }

    /** Closes the line: returned, lost or written off. */
    @PostMapping("/api/hr/it-assets/{id}/return")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<ItAssetAssignmentDto> returnAsset(
            @PathVariable Long id,
            @Valid @RequestBody ReturnAssetAssignmentRequest req,
            Authentication auth) {
        return ResponseEntity.ok(service.returnAsset(id, req, actorId(auth)));
    }

    /**
     * For a line entered by mistake only. Ending a possession is a RETURN — a delete
     * erases the fact that the employee ever held the item.
     */
    @DeleteMapping("/api/hr/it-assets/{id}")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        service.delete(id, actorId(auth));
        return ResponseEntity.noContent().build();
    }

    /** JWT sub = String.valueOf(userId) — see portal JwtTokenService. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
