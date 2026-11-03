package com.daf360.rh.controller;

import com.daf360.rh.dto.AuditEntryDto;
import com.daf360.rh.repository.AuditQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryRepository auditQueryRepository;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public Page<AuditEntryDto> logs(Pageable pageable) {
        return auditQueryRepository.findMerged(pageable).map(AuditEntryDto::from);
    }
}
