package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.dto.absence.AbsenceDto;
import com.daf360.rh.service.AbsenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hr/absences")
@RequiredArgsConstructor
public class AbsenceController {

    private final AbsenceService absenceService;

    /**
     * GET /api/hr/absences?profileId={id}&page=0&size=10
     * Paginated absences for a given employee profile.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AbsenceDto>> getAbsences(
            @RequestParam Long profileId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(
                PageResponse.from(absenceService.getAbsences(profileId, page, size)));
    }
}
