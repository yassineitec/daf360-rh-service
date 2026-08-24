package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.MissionChangeRequestType;
import com.daf360.rh.dto.mission.*;
import com.daf360.rh.service.MissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The mission process, all four desks on one controller because they act on one row.
 *
 * Three permissions, one per desk:
 *  - {@code RH_CREATE_MISSION}            — the manager who plans
 *  - {@code RH_MANAGE_MISSION_BILLETERIE} — RH, who prices and validates
 *  - {@code FACT_APPROVE_MISSION_COST}    — finance, who decides
 *
 * The {@code /my*} endpoints carry NO permission: every employee reads their own missions,
 * and the service checks the row belongs to the caller. Gating them on a permission would
 * mean granting a code to the whole company to let people see their own trip.
 *
 * The finance frontend calls this service directly on {@code environment.hrApiUrl} — same
 * arrangement as {@link CandidateCostApprovalController}, so nothing about a mission is
 * duplicated into DAF360_FACT.
 */
@RestController
@RequestMapping("/api/hr/missions")
@RequiredArgsConstructor
public class MissionController {

    private final MissionService service;

    // ── Manager ───────────────────────────────────────────────────────────────

    /** The caller's own team — who they may plan a mission for, and pick as responsable. */
    @GetMapping("/eligible-employees")
    @PreAuthorize("hasAuthority('RH_CREATE_MISSION')")
    public List<MissionEligibleEmployeeDto> eligibleEmployees(Authentication auth) {
        return service.eligibleEmployees(actorId(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RH_CREATE_MISSION')")
    public MissionDto create(@Valid @RequestBody CreateMissionRequest request, Authentication auth) {
        return service.create(request, actorId(auth));
    }

    /** Everything the caller planned. */
    @GetMapping
    @PreAuthorize("hasAuthority('RH_CREATE_MISSION')")
    public List<MissionDto> listMine(Authentication auth) {
        return service.listForManager(actorId(auth));
    }

    /** The manager calls it off — only while RH has not answered yet. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('RH_CREATE_MISSION')")
    public MissionDto cancel(@PathVariable Long id,
                             @Valid @RequestBody MissionDecisionRequest request,
                             Authentication auth) {
        return service.cancelByManager(id, request.getNotes(), actorId(auth));
    }

    // ── RH — billeterie ───────────────────────────────────────────────────────

    @GetMapping("/pending-hr")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public List<MissionDto> pendingHr() {
        return service.listPendingHr();
    }

    /** Creates the expense sheet on first call, updates it afterwards. */
    @PutMapping("/{id}/expenses")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public MissionDto saveExpenses(@PathVariable Long id,
                                   @Valid @RequestBody MissionExpenseDto request,
                                   Authentication auth) {
        return service.saveExpenses(id, request, actorId(auth));
    }

    /** RH corrects the plan to what the agency could actually book. Traced. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public MissionDto adjust(@PathVariable Long id,
                             @Valid @RequestBody CreateMissionRequest request,
                             Authentication auth) {
        return service.adjustByHr(id, request, actorId(auth));
    }

    @PostMapping("/{id}/hr-validate")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public MissionDto hrValidate(@PathVariable Long id,
                                 @Valid @RequestBody MissionDecisionRequest request,
                                 Authentication auth) {
        return service.validateByHr(id, request.getNotes(), actorId(auth));
    }

    @PostMapping("/{id}/hr-reject")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public MissionDto hrReject(@PathVariable Long id,
                               @Valid @RequestBody MissionDecisionRequest request,
                               Authentication auth) {
        return service.rejectByHr(id, request.getNotes(), actorId(auth));
    }

    // ── RH — the employees' asks ──────────────────────────────────────────────

    @GetMapping("/change-requests")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public List<MissionChangeRequestDto> pendingChangeRequests() {
        return service.listPendingChangeRequests();
    }

    @PostMapping("/change-requests/{requestId}/resolve")
    @PreAuthorize("hasAuthority('RH_MANAGE_MISSION_BILLETERIE')")
    public MissionChangeRequestDto resolveChangeRequest(@PathVariable Long requestId,
                                                        @Valid @RequestBody ResolveChangeRequestDto request,
                                                        Authentication auth) {
        return service.resolveChangeRequest(requestId, request, actorId(auth));
    }

    // ── Finance ───────────────────────────────────────────────────────────────

    @GetMapping("/pending-finance")
    @PreAuthorize("hasAuthority('FACT_APPROVE_MISSION_COST')")
    public List<MissionDto> pendingFinance() {
        return service.listPendingFinance();
    }

    @PostMapping("/{id}/finance-approve")
    @PreAuthorize("hasAuthority('FACT_APPROVE_MISSION_COST')")
    public MissionDto financeApprove(@PathVariable Long id,
                                     @Valid @RequestBody MissionDecisionRequest request,
                                     Authentication auth) {
        return service.approveByFinance(id, request.getNotes(), actorId(auth));
    }

    @PostMapping("/{id}/finance-reject")
    @PreAuthorize("hasAuthority('FACT_APPROVE_MISSION_COST')")
    public MissionDto financeReject(@PathVariable Long id,
                                    @Valid @RequestBody MissionDecisionRequest request,
                                    Authentication auth) {
        return service.rejectByFinance(id, request.getNotes(), actorId(auth));
    }

    // ── Employee — self-service and calendar ──────────────────────────────────

    /** The caller's own missions, every status. No permission: see the class javadoc. */
    @GetMapping("/my")
    public List<MissionDto> my(Authentication auth) {
        return service.listMine(actorId(auth));
    }

    /**
     * The shell's home calendar feed — APPROVED missions overlapping [from, to]. Overlapping
     * and not starting-inside, so a trip straddling a month boundary shows in both months.
     */
    @GetMapping("/my/calendar")
    public List<MissionCalendarEventDto> myCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        return service.myCalendar(actorId(auth), from, to);
    }

    @GetMapping("/my/{id}")
    public MissionDto myDetail(@PathVariable Long id, Authentication auth) {
        return service.getForEmployee(id, actorId(auth));
    }

    /** The employee asks to move the dates. */
    @PostMapping("/my/{id}/change-request")
    public MissionChangeRequestDto requestPeriodChange(@PathVariable Long id,
                                                       @Valid @RequestBody MissionChangeRequestCreate request,
                                                       Authentication auth) {
        return service.requestChange(id, MissionChangeRequestType.PERIOD_CHANGE, request, actorId(auth));
    }

    /**
     * The employee asks for the mission to be called off. A separate endpoint from the one
     * above rather than a `type` field, so a cancellation can never be submitted by
     * flipping a value on a period-change payload.
     */
    @PostMapping("/my/{id}/cancel-request")
    public MissionChangeRequestDto requestCancellation(@PathVariable Long id,
                                                       @Valid @RequestBody MissionChangeRequestCreate request,
                                                       Authentication auth) {
        return service.requestChange(id, MissionChangeRequestType.CANCELLATION, request, actorId(auth));
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    /** Detail for any of the three desks. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('RH_CREATE_MISSION','RH_MANAGE_MISSION_BILLETERIE','FACT_APPROVE_MISSION_COST')")
    public MissionDto detail(@PathVariable Long id) {
        return service.getById(id);
    }

    /**
     * The caller's user id, from the JWT subject. Same helper as
     * {@link CandidateCostApprovalController} — but WITHOUT its `return 1L` fallback: here
     * the id decides whose team is queried and whose mission may be cancelled, so a
     * silent fallback to user 1 would be an authorisation hole, not a convenience.
     */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Utilisateur non authentifié");
        }
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            throw new org.springframework.security.access.AccessDeniedException("Identité utilisateur illisible");
        }
    }
}
