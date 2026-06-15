package com.daf360.rh.controller;

import com.daf360.rh.dto.LeaveRequestDto;
import com.daf360.rh.dto.LeaveResponseDto;
import com.daf360.rh.service.LeaveService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hr/absences")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @GetMapping("/employee/{employeeId}")
    //@PreAuthorize("hasAnyAuthority('GET_LEAVES', 'GET_GLOBAL_LEAVES', 'RESPONSE_LEAVE', 'HR_UPDATE_PROFILE')")
    public List<LeaveResponseDto> byEmployee(@PathVariable Long employeeId) {
        return leaveService.findByEmployee(employeeId);
    }

    @GetMapping("/pending")
    //@PreAuthorize("hasAnyAuthority('GET_GLOBAL_LEAVES', 'RESPONSE_LEAVE', 'HR_UPDATE_PROFILE')")
    public Page<LeaveResponseDto> pending(Pageable pageable) {
        return leaveService.findPending(pageable);
    }

    @PostMapping
    //@PreAuthorize("hasAuthority('ADD_LEAVE')")
    public ResponseEntity<LeaveResponseDto> submit(
            @Valid @RequestBody LeaveRequestDto dto,
            @AuthenticationPrincipal String actorId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(leaveService.submit(dto, actorId));
    }

    @PutMapping("/{id}/approve-manager")
    //@PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public LeaveResponseDto approveManager(
            @PathVariable Long id,
            @RequestParam Long managerId,
            @AuthenticationPrincipal String actorId) {
        return leaveService.approveByManager(id, managerId, actorId);
    }

    @PutMapping("/{id}/approve-hr")
    //@PreAuthorize("hasAnyAuthority('RESPONSE_LEAVE', 'SETTLE_LEAVES')")
    public LeaveResponseDto approveHr(
            @PathVariable Long id,
            @RequestParam Long hrUserId,
            @AuthenticationPrincipal String actorId) {
        return leaveService.approveByHr(id, hrUserId, actorId);
    }

    @PutMapping("/{id}/reject")
    //@PreAuthorize("hasAuthority('RESPONSE_LEAVE')")
    public LeaveResponseDto reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String actorId) {
        return leaveService.reject(id, body.get("reason"), actorId);
    }
}
