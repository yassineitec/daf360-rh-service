package com.daf360.rh.controller;

import com.daf360.rh.dto.offboarding.OffboardingTaskCatalogDto;
import com.daf360.rh.dto.offboarding.SaveCatalogTaskDto;
import com.daf360.rh.service.OffboardingCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/admin/offboarding-catalog")
@RequiredArgsConstructor
/*
 * This class carried NO authorization at all, and SecurityConfig ends in
 * `.anyRequest().authenticated()` — so any signed-in user of any role could rewrite the
 * offboarding task catalog for every pays, including which tasks are blocking. The admin
 * UI hid the tab behind RH_MANAGE_OFFBOARDING; the API did not.
 */
@PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
public class OffboardingCatalogController {

    private final OffboardingCatalogService catalogService;

    @GetMapping
    public List<OffboardingTaskCatalogDto> list(
            @RequestParam Long paysId,
            @RequestParam(required = false) String contractType) {
        return catalogService.list(paysId, contractType);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OffboardingTaskCatalogDto create(@RequestBody @Valid SaveCatalogTaskDto dto) {
        return catalogService.create(dto);
    }

    @PutMapping("/{id}")
    public OffboardingTaskCatalogDto update(
            @PathVariable Long id,
            @RequestBody @Valid SaveCatalogTaskDto dto) {
        return catalogService.update(id, dto);
    }

    @PatchMapping("/{id}/toggle-active")
    public OffboardingTaskCatalogDto toggleActive(@PathVariable Long id) {
        return catalogService.toggleActive(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        catalogService.delete(id);
    }
}
