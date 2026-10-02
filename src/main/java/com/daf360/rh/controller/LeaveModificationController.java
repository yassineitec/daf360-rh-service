package com.daf360.rh.controller;

import com.daf360.rh.dto.leave.LeaveModificationCreate;
import com.daf360.rh.dto.leave.LeaveModificationDecision;
import com.daf360.rh.dto.leave.LeaveModificationDto;
import com.daf360.rh.service.LeaveModificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Demandes de modification / annulation d'un congé déjà validé.
 *
 * ROUTING: a sibling of /api/hr/leave rather than a branch of it. The two share a subject but
 * not a workflow, and nesting these under the congé's own id would suggest they are a
 * property of a request rather than a separate thing with its own queues.
 *
 * PERMISSIONS: the timesheet's four codes unchanged (REQUEST_LEAVE_MODIFICATION,
 * RESPOND_MODIFICATION_MANAGER, RESPOND_MODIFICATION_HR, GET_LEAVE_MODIFICATIONS), seeded by
 * V111 and granted by implication from the congé rights — see that script. Keeping the codes
 * means a role that already held them in the timesheet keeps working, and the permission
 * master list stays one list.
 *
 * WHICH STAGE IS BEING DECIDED IS NOT A PARAMETER. There are two decision endpoints, each
 * gated on its own code and each refusing a request that is not at its stage. A single
 * endpoint taking the stage in the payload would let a manager answer HR's question with
 * HR's effect.
 */
@RestController
@RequestMapping("/api/hr/leave-modifications")
@RequiredArgsConstructor
public class LeaveModificationController {

    private final LeaveModificationService service;

    /**
     * Sortable columns, as an allow-list.
     *
     * The value reaches a JPA {@code Sort}, which interpolates it into the query — anything
     * not named here is a field the caller invented. Same reasoning, and the same shape, as
     * LeaveRequestController's SORTABLE.
     */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "etat", "type", "nouvelleDateDebut", "dateValidation");

    // ═══ Filing ══════════════════════════════════════════════════════════════

    @PostMapping
    @PreAuthorize("hasAuthority('REQUEST_LEAVE_MODIFICATION')")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveModificationDto create(@Valid @RequestBody LeaveModificationCreate dto,
                                       @RequestParam(defaultValue = "fr") String lang,
                                       Authentication auth) {
        return service.create(dto, actorId(auth), lang);
    }

    // ═══ Deciding ════════════════════════════════════════════════════════════

    /** Stage 1. MODIFICATION only; agreeing passes it to HR and changes nothing yet. */
    @PutMapping("/{id}/manager-decision")
    @PreAuthorize("hasAuthority('RESPOND_MODIFICATION_MANAGER')")
    public LeaveModificationDto decideAsManager(@PathVariable Long id,
                                                @Valid @RequestBody LeaveModificationDecision dto,
                                                @RequestParam(defaultValue = "fr") String lang,
                                                Authentication auth) {
        return service.decideAsManager(id, dto, actorId(auth), lang);
    }

    /** Stage 2. The only call in this controller that changes a congé or a balance. */
    @PutMapping("/{id}/hr-decision")
    @PreAuthorize("hasAuthority('RESPOND_MODIFICATION_HR')")
    public LeaveModificationDto decideAsHr(@PathVariable Long id,
                                           @Valid @RequestBody LeaveModificationDecision dto,
                                           @RequestParam(defaultValue = "fr") String lang,
                                           Authentication auth) {
        return service.decideAsHr(id, dto, actorId(auth), lang);
    }

    // ═══ The three queues ════════════════════════════════════════════════════

    /**
     * The caller's own proposals.
     *
     * Gated on REQUEST_LEAVE_MODIFICATION, not GET_LEAVE_MODIFICATIONS: this returns only
     * rows the caller filed, so whoever may file may read their own back. Demanding the
     * broader read code would hide an employee's own history from them.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('REQUEST_LEAVE_MODIFICATION') or hasAuthority('GET_LEAVE_MODIFICATIONS')")
    public Map<String, Object> mine(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String dir,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        return envelope(service.mine(actorId(auth),
                PageRequest.of(page, size, sortOf(sort, dir)), lang));
    }

    /** The manager's queue — PENDING_MANAGER for this manager only. */
    @GetMapping("/manager-queue")
    @PreAuthorize("hasAuthority('RESPOND_MODIFICATION_MANAGER')")
    public Map<String, Object> managerQueue(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String dir,
                                            @RequestParam(defaultValue = "fr") String lang,
                                            Authentication auth) {
        return envelope(service.managerQueue(actorId(auth),
                PageRequest.of(page, size, sortOf(sort, dir)), lang));
    }

    /** HR's queue — PENDING_HR inside the caller's country perimeter. */
    @GetMapping("/hr-queue")
    @PreAuthorize("hasAuthority('RESPOND_MODIFICATION_HR')")
    public Map<String, Object> hrQueue(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       @RequestParam(defaultValue = "fr") String lang) {
        return envelope(service.hrQueue(PageRequest.of(page, size, sortOf(sort, dir)), lang));
    }

    /** Badge counts for both queues in one call. */
    @GetMapping("/counts")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> counts(Authentication auth) {
        return service.counts(actorId(auth));
    }

    /** Every proposal filed against one congé, for its detail view. */
    @GetMapping("/by-leave/{absenceId}")
    @PreAuthorize("hasAuthority('GET_LEAVE_MODIFICATIONS') or hasAuthority('GET_LEAVES')")
    public List<LeaveModificationDto> forLeave(@PathVariable Long absenceId,
                                               @RequestParam(defaultValue = "fr") String lang,
                                               Authentication auth) {
        return service.forLeave(absenceId, actorId(auth), lang);
    }

    // ═══ Plumbing ════════════════════════════════════════════════════════════

    private static Sort sortOf(String key, String dir) {
        String field = (key != null && SORTABLE.contains(key)) ? key : "createdAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }

    /** Same envelope every paginated rh endpoint returns. */
    private Map<String, Object> envelope(Page<LeaveModificationDto> rows) {
        return Map.of(
                "content", rows.getContent(),
                "page", rows.getNumber(),
                "size", rows.getSize(),
                "totalElements", rows.getTotalElements(),
                "totalPages", rows.getTotalPages());
    }

    /** The principal is the user id as a string — see JwtAuthFilter. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new AccessDeniedException("Utilisateur non authentifié");
        }
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("Identité utilisateur illisible");
        }
    }
}
