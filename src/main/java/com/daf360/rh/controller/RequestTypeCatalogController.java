package com.daf360.rh.controller;

import com.daf360.rh.dto.requests.RequestTypeCreateDto;
import com.daf360.rh.dto.requests.RequestTypeResponseDto;
import com.daf360.rh.service.RequestTypeCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/request-types")
@RequiredArgsConstructor
public class RequestTypeCatalogController {

    private final RequestTypeCatalogService typeService;

    /** GET /api/hr/request-types?paysId=1 */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RequestTypeResponseDto>> list(@RequestParam Long paysId) {
        return ResponseEntity.ok(typeService.list(paysId));
    }

    /** GET /api/hr/request-types/{id} */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<RequestTypeResponseDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(typeService.getById(id));
    }

    /** POST /api/hr/request-types — HR_ADMIN_ROLES only */
    @PostMapping
    @PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    public ResponseEntity<RequestTypeResponseDto> create(
            @Valid @RequestBody RequestTypeCreateDto dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(typeService.create(dto, auth));
    }

    /** PATCH /api/hr/request-types/{id} — HR_ADMIN_ROLES only */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    public ResponseEntity<RequestTypeResponseDto> update(
            @PathVariable Long id,
            @Valid @RequestBody RequestTypeCreateDto dto,
            Authentication auth) {
        return ResponseEntity.ok(typeService.update(id, dto, auth));
    }

    /** DELETE /api/hr/request-types/{id} — soft deactivate */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable Long id, Authentication auth) {
        typeService.deactivate(id, auth);
    }

    /** POST /api/hr/request-types/seed — Admin: run default seeding */
    @PostMapping("/seed")
    @PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seed() {
        typeService.seedDefaults();
    }
}
