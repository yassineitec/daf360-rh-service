package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.dto.leave.AutorisationCreateDto;
import com.daf360.rh.dto.leave.AutorisationResponseDto;
import com.daf360.rh.service.AutorisationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hr/autorisations")
@RequiredArgsConstructor
public class AutorisationController {

    private final AutorisationService autorisationService;

    @PostMapping("/{profileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AutorisationResponseDto> create(
            @PathVariable Long profileId,
            @Valid @RequestBody AutorisationCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(autorisationService.create(profileId, dto, auth));
    }

    @GetMapping("/{profileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<AutorisationResponseDto>> list(
            @PathVariable Long profileId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(autorisationService.list(profileId, pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<AutorisationResponseDto> approve(
            @PathVariable Long id,
            @RequestParam Long responsableId,
            Authentication auth) {
        return ResponseEntity.ok(autorisationService.approve(id, responsableId, auth));
    }

    @PostMapping("/{id}/refuse")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<AutorisationResponseDto> refuse(
            @PathVariable Long id,
            @RequestParam String motif,
            Authentication auth) {
        return ResponseEntity.ok(autorisationService.refuse(id, motif, auth));
    }
}
