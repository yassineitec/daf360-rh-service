package com.daf360.rh.controller;

import com.daf360.rh.dto.EmployeeRequestDto;
import com.daf360.rh.dto.EmployeeResponseDto;
import com.daf360.rh.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hr/employes")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    @PreAuthorize("hasAnyRole('HR', 'MANAGER', 'ADMIN')")
    public Page<EmployeeResponseDto> list(@PageableDefault(size = 20) Pageable pageable) {
        return employeeService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public EmployeeResponseDto get(@PathVariable Long id) {
        return employeeService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('HR', 'ADMIN')")
    public ResponseEntity<EmployeeResponseDto> create(
            @Valid @RequestBody EmployeeRequestDto dto,
            @AuthenticationPrincipal String actorId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(employeeService.create(dto, actorId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('HR', 'ADMIN')")
    public EmployeeResponseDto update(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeRequestDto dto,
            @AuthenticationPrincipal String actorId) {
        return employeeService.update(id, dto, actorId);
    }

    @DeleteMapping("/{id}/disable")
    @PreAuthorize("hasAnyRole('HR', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable Long id, @AuthenticationPrincipal String actorId) {
        employeeService.disable(id, actorId);
    }
}
