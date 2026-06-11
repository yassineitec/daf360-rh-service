package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.dto.leave.TeletravailCreateDto;
import com.daf360.rh.dto.leave.TeletravailResponseDto;
import com.daf360.rh.service.TeletravailService;
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
@RequestMapping("/api/hr/teletravails")
@RequiredArgsConstructor
public class TeletravailController {

    private final TeletravailService teletravailService;

    @PostMapping("/{profileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TeletravailResponseDto> create(
            @PathVariable Long profileId,
            @Valid @RequestBody TeletravailCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teletravailService.create(profileId, dto, auth));
    }

    @GetMapping("/{profileId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<TeletravailResponseDto>> list(
            @PathVariable Long profileId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PageResponse.from(teletravailService.list(profileId, pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<TeletravailResponseDto> approve(
            @PathVariable Long id,
            @RequestParam Long responsableId,
            Authentication auth) {
        return ResponseEntity.ok(teletravailService.approve(id, responsableId, auth));
    }

    @PostMapping("/{id}/refuse")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<TeletravailResponseDto> refuse(
            @PathVariable Long id,
            @RequestParam String motif,
            Authentication auth) {
        return ResponseEntity.ok(teletravailService.refuse(id, motif, auth));
    }
}
