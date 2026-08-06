package com.daf360.rh.controller;

import com.daf360.rh.domain.OffboardingValidator;
import com.daf360.rh.repository.OffboardingValidatorRepository;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin of the per-pays offboarding validator (V66) — which role may give the RH validation of
 * a departure in a given country.
 *
 * Behind `RH_MANAGE_OFFBOARDING` at class level, like `OffboardingCatalogController`: deciding
 * who validates departures is as sensitive as editing the task catalog, and
 * `SecurityConfig` ends in `.anyRequest().authenticated()` — an unannotated controller here
 * would be open to every signed-in user.
 *
 * PUT rather than POST/PATCH: there is at most one row per pays (unique constraint), so the
 * operation is "set the validator of this country", which is idempotent by nature.
 */
@RestController
@RequestMapping("/api/hr/admin/offboarding-validators")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'RH_MANAGE_OFFBOARDING')")
public class OffboardingValidatorController {

    private final OffboardingValidatorRepository repo;
    private final JdbcTemplate jdbc;

    /** The role name is joined in so the screen can render it without a second call. */
    private static final String ROLE_NAME_SQL =
        "SELECT frenchName FROM [dbo].[Roles] WHERE id = ?";

    @Data
    public static class ValidatorDto {
        private Long   id;
        private Long   paysId;
        private Long   roleId;
        private String roleName;
    }

    @Data
    public static class SaveValidatorRequest {
        @NotNull
        private Long roleId;
    }

    /**
     * The configured validator of a pays, or 204 when none is set.
     *
     * 204 rather than 404: "no validator configured" is a valid, supported state — it means the
     * pays falls back to the permission check (see V66) — not a missing resource.
     */
    @GetMapping("/{paysId}")
    public ResponseEntity<ValidatorDto> get(@PathVariable Long paysId) {
        return repo.findByPaysId(paysId)
            .map(v -> ResponseEntity.ok(toDto(v)))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    public List<ValidatorDto> list() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    @PutMapping("/{paysId}")
    @Transactional
    public ValidatorDto set(@PathVariable Long paysId,
                            @RequestBody SaveValidatorRequest request,
                            Authentication auth) {
        OffboardingValidator entity = repo.findByPaysId(paysId)
            .orElseGet(() -> OffboardingValidator.builder()
                .paysId(paysId)
                .createdAt(OffsetDateTime.now())
                .build());
        entity.setRoleId(request.getRoleId());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setUpdatedBy(actorId(auth));
        return toDto(repo.save(entity));
    }

    /** Clearing the row is how a pays goes back to the permission-only rule. */
    @DeleteMapping("/{paysId}")
    @Transactional
    public ResponseEntity<Void> clear(@PathVariable Long paysId) {
        repo.findByPaysId(paysId).ifPresent(repo::delete);
        return ResponseEntity.noContent().build();
    }

    private ValidatorDto toDto(OffboardingValidator v) {
        ValidatorDto dto = new ValidatorDto();
        dto.setId(v.getId());
        dto.setPaysId(v.getPaysId());
        dto.setRoleId(v.getRoleId());
        dto.setRoleName(resolveRoleName(v.getRoleId()));
        return dto;
    }

    private String resolveRoleName(Long roleId) {
        if (roleId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(ROLE_NAME_SQL, roleId);
            return rows.isEmpty() ? null : (String) rows.get(0).get("frenchName");
        } catch (Exception ex) {
            return null;
        }
    }

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
