package com.daf360.rh.controller;

import com.daf360.rh.dto.admin.ParameterDto;
import com.daf360.rh.dto.admin.ParameterResponseDto;
import com.daf360.rh.service.ParameterSetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/admin/parameters")
@RequiredArgsConstructor
public class ParameterSetController {

    private final ParameterSetService paramService;

    /** GET /api/hr/admin/parameters?pays=1 */
    @GetMapping
    //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<List<ParameterResponseDto>> list(@RequestParam Long pays) {
        return ResponseEntity.ok(paramService.list(pays));
    }

    /** POST /api/hr/admin/parameters — HR_ADMIN_ROLES only */
    @PostMapping
    //@PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    public ResponseEntity<ParameterResponseDto> create(
            @Valid @RequestBody ParameterDto dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paramService.create(dto, auth));
    }

    /** PATCH /api/hr/admin/parameters/{id} — HR_ADMIN_ROLES only */
    @PatchMapping("/{id}")
    //@PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    public ResponseEntity<ParameterResponseDto> update(
            @PathVariable Long id,
            @Valid @RequestBody ParameterDto dto,
            Authentication auth) {
        return ResponseEntity.ok(paramService.update(id, dto, auth));
    }

    /** DELETE /api/hr/admin/parameters/{id} — HR_ADMIN_ROLES only */
    @DeleteMapping("/{id}")
    //@PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        paramService.delete(id, auth);
    }

    /** POST /api/hr/admin/parameters/seed — trigger default seeding */
    @PostMapping("/seed")
    //@PreAuthorize("hasAuthority('HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seed() {
        paramService.seedDefaults();
    }
}
