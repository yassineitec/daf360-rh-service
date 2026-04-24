package com.daf360.rh.controller;

import com.daf360.rh.dto.PaySlipResponseDto;
import com.daf360.rh.service.PaySlipService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/paie")
@RequiredArgsConstructor
public class PaySlipController {

    private final PaySlipService paySlipService;

    @GetMapping("/employee/{employeeId}/bulletins")
    @PreAuthorize("hasAnyRole('HR', 'EMPLOYEE', 'ADMIN')")
    public List<PaySlipResponseDto> list(@PathVariable Long employeeId) {
        return paySlipService.findByEmployee(employeeId);
    }

    @PutMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('HR', 'ADMIN')")
    public PaySlipResponseDto publish(@PathVariable Long id,
                                      @AuthenticationPrincipal String actorId) {
        return paySlipService.publish(id, actorId);
    }
}
