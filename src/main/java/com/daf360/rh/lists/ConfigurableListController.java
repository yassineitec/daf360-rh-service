package com.daf360.rh.lists;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for configurable reference lists.
 *
 * <p>GET /types and GET /{listTypeCode} carry NO @PreAuthorize — they are used
 * by all form screens and need only an authenticated JWT (enforced globally by
 * SecurityConfig).
 *
 * <p>Write operations delegate their own @PreAuthorize("hasAuthority('ADMIN_LISTS')")
 * to the service layer so the restriction is enforced regardless of call origin.
 */
@RestController
@RequestMapping("/api/hr/lists")
@RequiredArgsConstructor
public class ConfigurableListController {

    private final ConfigurableListService listService;

    // ── Read endpoints (available to all authenticated users) ─────────────────

    /**
     * GET /api/hr/lists/types
     * Returns all list type definitions ordered by code.
     */
    @GetMapping("/types")
    public List<ListTypeResponse> getListTypes() {
        return listService.getListTypes();
    }

    /**
     * GET /api/hr/lists/{listTypeCode}?pays={paysId}
     * Returns active values for the given list type.
     * When paysId is absent and the list is isPerPays, only global values (paysId IS NULL) are returned.
     */
    @GetMapping("/{listTypeCode}")
    public List<ListValueResponse> getListValues(
            @PathVariable String listTypeCode,
            @RequestParam(name = "pays", required = false) Long paysId) {
        return listService.getListValues(listTypeCode, paysId);
    }

    /**
     * GET /api/hr/lists/{listTypeId}/values/all
     * Admin view — returns all values (including inactive) for the given list type.
     */
    @GetMapping("/{listTypeId}/values/all")
    public List<ListValueResponse> getAllValuesForAdmin(@PathVariable Long listTypeId) {
        return listService.getAllValuesForAdmin(listTypeId);
    }

    // ── Write endpoints (ADMIN_LISTS permission — enforced in service) ─────────

    /**
     * POST /api/hr/lists/values
     * Create a new list value.
     */
    @PostMapping("/values")
    @ResponseStatus(HttpStatus.CREATED)
    public ListValueResponse createListValue(
            @Valid @RequestBody CreateListValueRequest dto,
            Authentication auth) {
        return listService.createListValue(dto, actorId(auth));
    }

    /**
     * PATCH /api/hr/lists/values/{id}
     * Partially update a list value (labels, sort order, active flag).
     */
    @PatchMapping("/values/{id}")
    public ListValueResponse updateListValue(
            @PathVariable Long id,
            @Valid @RequestBody UpdateListValueRequest dto,
            Authentication auth) {
        return listService.updateListValue(id, dto, actorId(auth));
    }

    /**
     * DELETE /api/hr/lists/values/{id}
     * Hard-delete a non-system list value.
     */
    @DeleteMapping("/values/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteListValue(@PathVariable Long id, Authentication auth) {
        listService.deleteListValue(id, actorId(auth));
    }

    /**
     * PATCH /api/hr/lists/{listTypeId}/reorder
     * Re-order the values of a list type by providing the desired ID sequence.
     */
    @PatchMapping("/{listTypeId}/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderListValues(
            @PathVariable Long listTypeId,
            @Valid @RequestBody ReorderRequest dto,
            Authentication auth) {
        listService.reorderListValues(listTypeId, dto.getOrderedIds(), actorId(auth));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
