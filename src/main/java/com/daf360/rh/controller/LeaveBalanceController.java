package com.daf360.rh.controller;

import com.daf360.rh.dto.leave.AdjustBalanceDto;
import com.daf360.rh.dto.leave.LeaveBalanceResponseDto;
import com.daf360.rh.service.LeaveBalanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/profiles/{profileId}/leave-balances")
@RequiredArgsConstructor
public class LeaveBalanceController {

    private final LeaveBalanceService balanceService;

    /**
     * GET /api/hr/profiles/{profileId}/leave-balances?annee=2026
     * Returns all leave balances for the employee for the given year.
     */
    @GetMapping
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<LeaveBalanceResponseDto>> get(
            @PathVariable Long profileId,
            @RequestParam Integer annee) {
        return ResponseEntity.ok(balanceService.getBalances(profileId, annee));
    }

    /**
     * POST /api/hr/profiles/{profileId}/leave-balances/adjust
     * HR Manager manual adjustment (positive delta = add days, negative = deduct).
     * Required: HR_MANAGER
     */
    @PostMapping("/adjust")
    //@PreAuthorize("hasAnyAuthority('SETTLE_LEAVES', 'HR_ADMIN_ROLES')")
    public ResponseEntity<LeaveBalanceResponseDto> adjust(
            @PathVariable Long profileId,
            @RequestParam Long balanceId,
            @Valid @RequestBody AdjustBalanceDto dto,
            Authentication auth) {
        return ResponseEntity.ok(balanceService.adjustBalance(balanceId, dto, auth));
    }
}
