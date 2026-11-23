package com.daf360.rh.controller;

import com.daf360.rh.dto.AuditEntryDto;
import com.daf360.rh.repository.AuditQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/hr/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryRepository auditQueryRepository;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public Page<AuditEntryDto> logs(Pageable pageable) {
        return auditQueryRepository.findMerged(pageable).map(AuditEntryDto::from);
    }

    /**
     * GET /api/hr/audit/users?ids=10001,10002 — resolves `Users.id` to a display name.
     *
     * Exists for the audit detail view: a changed row's own JSON can carry an actor-reference
     * column (`validated_by`, `completed_by`...) that is a bare `Users.id`, meaningless on
     * screen without a name. Gated the same as the audit log itself, not GET_USERS — this
     * returns name/email only, for whichever ids a diff already shows, not a user directory.
     */
    @GetMapping("/users")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public List<AuditActorDto> users(@RequestParam List<Long> ids) {
        if (ids.isEmpty()) return Collections.emptyList();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, fullName, email FROM Users WHERE id IN (" + placeholders + ")";
        return jdbcTemplate.query(sql, (rs, rn) -> new AuditActorDto(
                rs.getLong("id"), rs.getString("fullName"), rs.getString("email")
        ), ids.toArray());
    }

    public record AuditActorDto(Long id, String fullName, String email) {}
}
