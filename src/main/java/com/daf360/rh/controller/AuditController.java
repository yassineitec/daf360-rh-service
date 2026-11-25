package com.daf360.rh.controller;

import com.daf360.rh.dto.AuditEntryDto;
import com.daf360.rh.repository.AuditSearchRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/hr/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditSearchRepository auditSearchRepository;
    private final TenantService tenantService;
    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_PAGE_SIZE = 200;

    /**
     * GET /api/hr/audit/logs — the merged timeline, newest first, every filter optional.
     *
     * <p>{@code paysId} narrows to one entity (plus the company-wide rows, which have none).
     * A caller who does not see every country is always held to their own perimeter, whatever
     * {@code paysId} asks for — see {@link TenantService#getPaysScope()}.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public Page<AuditEntryDto> logs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) List<String> actions,
            @RequestParam(required = false) List<String> entityTypes,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Long paysId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return auditSearchRepository.search(
                new AuditSearchRepository.Criteria(q, from, to, source, actions, entityTypes, actor, paysScope(paysId)),
                safePage, safeSize);
    }

    /** GET /api/hr/audit/logs/facets — the values the screen's filter selects offer. */
    @GetMapping("/logs/facets")
    @PreAuthorize("hasAnyAuthority('HR_ADMIN_ROLES', 'HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE')")
    public AuditFacetsDto facets() {
        return new AuditFacetsDto(
                auditSearchRepository.distinctEntityTypes(),
                auditSearchRepository.distinctActors(),
                auditSearchRepository.distinctEventActions());
    }

    public record AuditFacetsDto(List<String> entityTypes, List<String> actors, List<String> eventActions) {}

    /** null = no country filter. A restricted caller never gets null, nor a pays outside their scope. */
    private Collection<Long> paysScope(Long requested) {
        PaysScopeContext.Scope scope = tenantService.getPaysScope();
        if (scope.all() || scope.paysIds().isEmpty()) {
            // Whole perimeter (or unresolved token — permissive, as everywhere else).
            return requested != null ? Set.of(requested) : null;
        }
        if (requested != null && scope.paysIds().contains(requested)) return Set.of(requested);
        return scope.paysIds();
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
