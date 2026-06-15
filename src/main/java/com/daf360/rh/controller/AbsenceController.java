package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.dto.leave.AbsenceCreateDto;
import com.daf360.rh.dto.leave.AbsenceFilterDto;
import com.daf360.rh.dto.leave.AbsenceResponseDto;
import com.daf360.rh.service.AbsenceService;
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
@RequiredArgsConstructor
public class AbsenceController {

    private final AbsenceService absenceService;

    /**
     * POST /api/hr/absences/{profileId}
     * Employee or HR submits an absence request.
     */
    @PostMapping("/api/hr/absences/{profileId}")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<AbsenceResponseDto> create(
            @PathVariable Long profileId,
            @Valid @RequestBody AbsenceCreateDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(absenceService.createAbsenceRequest(profileId, dto, auth));
    }

    /**
     * GET /api/hr/absences?profileId=&etatDemande=&dateFrom=&dateTo=&page=0&size=20
     */
    @GetMapping("/api/hr/absences")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<AbsenceResponseDto>> list(
            @RequestParam(required = false) Long   profileId,
            @RequestParam(required = false) String etatDemande,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @PageableDefault(size = 20) Pageable pageable) {

        AbsenceFilterDto filter = new AbsenceFilterDto();
        filter.setProfileId(profileId);
        filter.setEtatDemande(etatDemande);
        if (dateFrom != null) filter.setDateFrom(java.time.LocalDate.parse(dateFrom));
        if (dateTo   != null) filter.setDateTo(java.time.LocalDate.parse(dateTo));

        return ResponseEntity.ok(PageResponse.from(absenceService.listAbsences(filter, pageable)));
    }

    /**
     * POST /api/hr/absences/{id}/approve?responsableId=
     * Approve and deduct leave balance.
     * Required: HR_MANAGER or MANAGER
     */
    @PostMapping("/api/hr/absences/{id}/approve")
    //@PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<AbsenceResponseDto> approve(
            @PathVariable Long id,
            @RequestParam Long responsableId,
            Authentication auth) {
        return ResponseEntity.ok(absenceService.approveAbsence(id, responsableId, auth));
    }

    /**
     * POST /api/hr/absences/{id}/refuse?motif=
     * Refuse and restore leave balance if was previously approved.
     * Required: HR_MANAGER or MANAGER
     */
    @PostMapping("/api/hr/absences/{id}/refuse")
    //@PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public ResponseEntity<AbsenceResponseDto> refuse(
            @PathVariable Long id,
            @RequestParam String motif,
            Authentication auth) {
        return ResponseEntity.ok(absenceService.refuseAbsence(id, motif, auth));
    }
}
