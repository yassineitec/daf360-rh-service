package com.daf360.rh.controller;

import com.daf360.rh.dto.leave.AbsenceTypeDto;
import com.daf360.rh.dto.leave.AbsenceTypeUpsert;
import com.daf360.rh.service.AbsenceTypeAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Administering the leave-type catalogue.
 *
 * Separate from LeaveRequestController on purpose: that one is used by every employee who
 * asks for leave, this one by the handful of people who decide what leave means. Mixing them
 * would put a catalogue-management permission on a controller most of the company calls.
 *
 * Gated on CREATE_ABSENCE_TYPE throughout — including the reads. The catalogue's *contents*
 * are already public to any employee through `/api/hr/leave/types`; what is restricted here
 * is seeing the configuration — which types silently draw on a balance, which are hidden
 * from managers — and that is administrative information.
 */
@RestController
@RequestMapping("/api/hr/leave/admin/types")
@RequiredArgsConstructor
public class AbsenceTypeController {

    private final AbsenceTypeAdminService service;

    /** The whole live catalogue, active and inactive. */
    @GetMapping
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public List<AbsenceTypeDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public AbsenceTypeDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public AbsenceTypeDto create(@Valid @RequestBody AbsenceTypeUpsert body) {
        return service.create(body);
    }

    /** The submitted `code` is ignored — a code is write-once. See AbsenceTypeAdminService. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public AbsenceTypeDto update(@PathVariable Long id, @Valid @RequestBody AbsenceTypeUpsert body) {
        return service.update(id, body);
    }

    /**
     * Activate or deactivate without opening the form.
     *
     * Deactivating withdraws the type from new requests and leaves every filed request
     * untouched — the reversible half of retiring one, and what an admin usually wants.
     */
    @PutMapping("/{id}/active")
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public AbsenceTypeDto setActive(@PathVariable Long id, @RequestParam boolean value) {
        return service.setActive(id, value);
    }

    /**
     * How many requests already use this code.
     *
     * The screen calls this before offering to retire a type, so the confirmation can say
     * what is affected rather than asking for a decision with no information.
     */
    @GetMapping("/{id}/usage")
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public Map<String, Object> usage(@PathVariable Long id) {
        AbsenceTypeDto t = service.get(id);
        return Map.of("code", t.code(), "requests", service.countRequestsUsing(t.code()));
    }

    /**
     * Retire a type — soft delete.
     *
     * DELETE by verb, soft by implementation, and deliberately not a hard one: the requests
     * filed under this code still need it to resolve a label, a balance rule and a refund.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CREATE_ABSENCE_TYPE')")
    public void retire(@PathVariable Long id) {
        service.retire(id);
    }
}
