package com.daf360.rh.controller;

import com.daf360.rh.domain.LeaveRequest;
import com.daf360.rh.domain.enums.DemandeEtat;
import com.daf360.rh.dto.leave.*;
import com.daf360.rh.mapper.LeaveRequestMapper;
import com.daf360.rh.service.LeaveRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Congés, for every desk that touches them.
 *
 * ROUTING: mounted under /api/hr, which the shell's nginx already proxies to rh-backend.
 * The timesheet served these from /authorized/absence on its own backend and needed its own
 * proxy rule; this needs none.
 *
 * PERMISSIONS: the timesheet's codes are carried across unchanged and enforced with
 * hasAuthority, which is what the newest rh modules use and what the portal already mints
 * into the token. Nine of the fourteen are new to RH's catalogue and are seeded by
 * V105__leave_permissions.sql; the other five (ADD_LEAVE, GET_LEAVES, RESPONSE_LEAVE,
 * SETTLE_LEAVES, GET_GLOBAL_LEAVES) were already there.
 *
 * SETTLE_LEAVES IS THE OVERRIDE. It appears throughout as `canSettle`, and it is what lets
 * HR edit a decided request, decide one they are not the responsable for, and create a
 * request on someone else's behalf. Every endpoint that honours it reads it from the token
 * rather than taking it as a parameter.
 */
@RestController
@RequestMapping("/api/hr/leave")
@RequiredArgsConstructor
public class LeaveRequestController {

    private final LeaveRequestService service;
    private final LeaveRequestMapper mapper;

    // ═══ The request form ════════════════════════════════════════════════════

    /**
     * Everything the self-service modal needs: balances, the type and category options, the
     * approver list and the ranges already taken. One call, because the form cannot render
     * usefully without all five.
     */
    @GetMapping("/headers")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public LeaveHeadersDto headers(@RequestParam(defaultValue = "fr") String lang, Authentication auth) {
        return service.headers(actorId(auth), lang);
    }

    /** Just the balances — for the self-service card, which shows them without opening the form. */
    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public LeaveBalancesDto balances(Authentication auth) {
        return service.balancesOf(actorId(auth));
    }

    @GetMapping("/blocking-dates")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('GET_LEAVES')")
    public List<LeaveBlockingRangeDto> blockingDates(Authentication auth) {
        return service.blockingRanges(actorId(auth));
    }

    /**
     * The caller's own leave overlapping a window — the shell's home calendar.
     *
     * NOT permission-gated, deliberately, and for the same reason as the missions calendar
     * feed next to it: it answers for the caller only, and every employee may see their own
     * leave on their own calendar. Requiring GET_LEAVES here would blank the calendar for
     * anyone who can request leave but not read a queue, which is most of the company.
     */
    @GetMapping("/my/calendar")
    public List<LeaveCalendarEventDto> myCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "fr") String lang,
            Authentication auth) {
        return service.myCalendar(actorId(auth), from, to, lang);
    }

    /**
     * The selectable type catalogue, for filter dropdowns on the RH screens.
     *
     * Separate from /headers, which resolves per-employee approvers and balances and is
     * therefore about one person. A filter only needs codes and labels.
     */
    @GetMapping("/types")
    @PreAuthorize("hasAuthority('GET_LEAVES') or hasAuthority('GET_GLOBAL_LEAVES')")
    public List<LeaveOptionDto> types(@RequestParam(defaultValue = "fr") String lang) {
        return service.typeCatalogue(lang);
    }

    // ═══ The employee's own ══════════════════════════════════════════════════

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADD_LEAVE')")
    public LeaveRequestDto create(@Valid @RequestBody LeaveRequestCreate body,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.create(body, actorId(auth)), lang);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('GET_LEAVES')")
    public Map<String, Object> mine(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        Page<LeaveRequest> rows = service.myRequests(actorId(auth), PageRequest.of(page, size));
        return pageResponse(rows, lang);
    }

    /** Header tiles: how many of the caller's requests sit in each state. */
    @GetMapping("/mine/counts")
    @PreAuthorize("hasAuthority('GET_LEAVES')")
    public Map<String, Long> myCounts(Authentication auth) {
        return service.stateCounts(actorId(auth));
    }

    /**
     * Edit. The service decides whether the caller may: their own pending request, or
     * anything at all with SETTLE_LEAVES.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADD_LEAVE') or hasAuthority('SETTLE_LEAVES')")
    public LeaveRequestDto update(@PathVariable Long id,
                                  @Valid @RequestBody LeaveRequestCreate body,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.update(id, body, actorId(auth), canSettle(auth)), lang);
    }

    // ═══ The manager's queue ═════════════════════════════════════════════════

    @GetMapping("/queue")
    @PreAuthorize("hasAuthority('GET_LEAVES')")
    public Map<String, Object> queue(@RequestParam(required = false) DemandeEtat etat,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long collaborateurId,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     @RequestParam(defaultValue = "fr") String lang,
                                     Authentication auth) {
        Page<LeaveRequest> rows = service.managerQueue(
                actorId(auth), etat, from, to, collaborateurId, type, PageRequest.of(page, size));
        return pageResponse(rows, lang);
    }

    @PutMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public LeaveRequestDto decide(@PathVariable Long id,
                                  @Valid @RequestBody LeaveDecisionRequest body,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.decide(id, body, actorId(auth), canSettle(auth)), lang);
    }

    /**
     * Approve the whole queue under the same filters the manager is looking at.
     *
     * Returns 200 with a per-row failure list rather than failing the call: partial success
     * is the expected outcome, not an error.
     */
    @PutMapping("/bulk-approve")
    @PreAuthorize("hasAuthority('BULK_APPROVE_LEAVES')")
    public BulkApproveResultDto bulkApprove(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) Long collaborateurId,
                                            @RequestParam(required = false) String type,
                                            Authentication auth) {
        return service.bulkApprove(actorId(auth), from, to, collaborateurId, type, canSettle(auth));
    }

    // ═══ Team and country ════════════════════════════════════════════════════

    @GetMapping("/team")
    @PreAuthorize("hasAuthority('GET_EMPLOYEES_LEAVES')")
    public Map<String, Object> team(@RequestParam(required = false) DemandeEtat etat,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(defaultValue = "fr") String lang,
                                    Authentication auth) {
        Page<LeaveRequest> rows = service.teamHistory(actorId(auth), etat, from, to, PageRequest.of(page, size));
        return pageResponse(rows, lang);
    }

    @GetMapping("/global")
    @PreAuthorize("hasAuthority('GET_GLOBAL_LEAVES')")
    public Map<String, Object> global(@RequestParam(required = false) Long paysId,
                                      @RequestParam(required = false) DemandeEtat etat,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) Long collaborateurId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "fr") String lang) {
        Page<LeaveRequest> rows = service.global(paysId, etat, type, from, to, collaborateurId,
                PageRequest.of(page, size));
        return pageResponse(rows, lang);
    }

    // ═══ HR ══════════════════════════════════════════════════════════════════

    /**
     * Régularisation — HR creating a congé for someone else, in any state and on any date.
     * Separate from POST / precisely so that the employee's own endpoint cannot take a
     * collaborateurId.
     */
    @PostMapping("/settle/{collaborateurId}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTLE_LEAVES')")
    public LeaveRequestDto settle(@PathVariable Long collaborateurId,
                                  @Valid @RequestBody LeaveRequestCreate body,
                                  @RequestParam(defaultValue = "fr") String lang,
                                  Authentication auth) {
        return mapper.toDto(service.createForEmployee(body, collaborateurId, actorId(auth)), lang);
    }

    /**
     * The régularisations already filed.
     *
     * `mine` defaults to true: the screen opens on what this person did, not on an audit of
     * everyone. Same envelope and parameter names as the other paginated lists here.
     */
    @GetMapping("/settle")
    @PreAuthorize("hasAuthority('SETTLE_LEAVES')")
    public Map<String, Object> settled(@RequestParam(defaultValue = "true") boolean mine,
                                       @RequestParam(required = false) DemandeEtat etat,
                                       @RequestParam(required = false) Long collaborateurId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(defaultValue = "fr") String lang,
                                       Authentication auth) {
        Page<LeaveRequest> rows = service.settled(actorId(auth), mine, etat, collaborateurId,
                from, to, PageRequest.of(page, size));
        return pageResponse(rows, lang);
    }

    /** Balances for someone else — the régularisation form needs them before it can judge. */
    @GetMapping("/balances/{collaborateurId}")
    @PreAuthorize("hasAuthority('SETTLE_LEAVES') or hasAuthority('GET_EMPLOYEES_LEAVES')")
    public LeaveBalancesDto balancesOf(@PathVariable Long collaborateurId) {
        return service.balancesOf(collaborateurId);
    }

    /**
     * /headers, but for the employee being régularisé rather than for the caller.
     *
     * The settle form asks the same five questions the self-service modal does — what may
     * this person take, how much is left, who signs it, what is already booked — only about
     * someone else. Reusing the same service call means the régularisation form cannot drift
     * from the employee's own form: a type capped at 5 days is capped in both.
     */
    @GetMapping("/headers/{collaborateurId}")
    @PreAuthorize("hasAuthority('SETTLE_LEAVES')")
    public LeaveHeadersDto headersOf(@PathVariable Long collaborateurId,
                                     @RequestParam(defaultValue = "fr") String lang) {
        return service.headers(collaborateurId, lang);
    }

    /**
     * Archive. The only removal there is — a congé stays part of the employee's record even
     * once cancelled, and an approved one has its days refunded on the way out.
     */
    @PutMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('DELETE_LEAVE')")
    public LeaveRequestDto archive(@PathVariable Long id,
                                   @RequestParam(defaultValue = "fr") String lang,
                                   Authentication auth) {
        return mapper.toDto(service.archive(id, actorId(auth)), lang);
    }

    // ═══ Helpers ═════════════════════════════════════════════════════════════

    /** Same envelope every paginated rh endpoint returns. */
    private Map<String, Object> pageResponse(Page<LeaveRequest> rows, String lang) {
        return Map.of(
                "content", mapper.toDtos(rows.getContent(), lang),
                "page", rows.getNumber(),
                "size", rows.getSize(),
                "totalElements", rows.getTotalElements(),
                "totalPages", rows.getTotalPages());
    }

    /**
     * The principal is the user id as a string — see JwtAuthFilter, and MissionController,
     * which resolves it the same way.
     */
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

    /** Read from the token rather than trusted from a parameter. */
    private boolean canSettle(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "SETTLE_LEAVES".equals(a.getAuthority()));
    }
}
