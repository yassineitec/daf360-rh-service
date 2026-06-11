package com.daf360.rh.controller;

import com.daf360.rh.dto.admin.HolidayCreateDto;
import com.daf360.rh.dto.admin.HolidayResponseDto;
import com.daf360.rh.service.HolidayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/hr/admin/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    /** GET /api/hr/admin/holidays?pays=1&year=2026 */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<HolidayResponseDto>> list(
            @RequestParam Long    pays,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(holidayService.list(pays, year));
    }

    /** POST /api/hr/admin/holidays — requires CREATE_HOLIDAY */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('CREATE_HOLIDAY', 'HR_ADMIN_ROLES')")
    public ResponseEntity<HolidayResponseDto> create(
            @Valid @RequestBody HolidayCreateDto dto, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(holidayService.create(dto, auth));
    }

    /** PATCH /api/hr/admin/holidays/{id} */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('UPDATE_HOLIDAY', 'HR_ADMIN_ROLES')")
    public ResponseEntity<HolidayResponseDto> update(
            @PathVariable Long id,
            @Valid @RequestBody HolidayCreateDto dto,
            Authentication auth) {
        return ResponseEntity.ok(holidayService.update(id, dto, auth));
    }

    /** DELETE /api/hr/admin/holidays/{id} — soft delete */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('DELETE_HOLIDAY', 'HR_ADMIN_ROLES')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        holidayService.delete(id, auth);
    }

    /**
     * GET /api/hr/admin/holidays/working-day?pays=1&date=2026-06-15
     * Returns true if the date is a working day for the given pays.
     */
    @GetMapping("/working-day")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Boolean> isWorkingDay(
            @RequestParam Long pays,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(holidayService.isWorkingDay(date, pays));
    }
}
