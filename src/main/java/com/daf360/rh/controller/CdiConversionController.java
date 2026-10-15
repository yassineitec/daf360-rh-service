package com.daf360.rh.controller;

import com.daf360.rh.dto.lifecycle.CdiConversionDecisionRequest;
import com.daf360.rh.dto.lifecycle.CdiConversionDto;
import com.daf360.rh.dto.lifecycle.SubmitCdiConversionRequest;
import com.daf360.rh.lifecycle.CdiConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CIVP → CDI conversion requests (V119). Permissions are checked in
 * {@link CdiConversionService} because the one required depends on the request's status
 * (validator for SUBMITTED, arbiter for ESCALATED).
 */
@RestController
@RequiredArgsConstructor
public class CdiConversionController {

    private final CdiConversionService service;

    /** Files a request on an active CIVP contract. */
    @PostMapping("/api/hr/contracts/{contractId}/cdi-conversion")
    @ResponseStatus(HttpStatus.CREATED)
    public CdiConversionDto submit(@PathVariable Long contractId,
                                   @Valid @RequestBody SubmitCdiConversionRequest dto,
                                   Authentication auth) {
        return service.submit(contractId, dto, actorId(auth));
    }

    /** Latest request of a contract — 204 when there is none. */
    @GetMapping("/api/hr/contracts/{contractId}/cdi-conversion")
    public ResponseEntity<CdiConversionDto> latestForContract(@PathVariable Long contractId) {
        return service.latestForContract(contractId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** {@code todo=true}: the requests waiting for the caller; otherwise all visible ones. */
    @GetMapping("/api/hr/cdi-conversions")
    public List<CdiConversionDto> list(@RequestParam(defaultValue = "false") boolean todo, Authentication auth) {
        return service.list(todo, actorId(auth));
    }

    @GetMapping("/api/hr/cdi-conversions/{id}")
    public CdiConversionDto get(@PathVariable Long id, Authentication auth) {
        return service.get(id, actorId(auth));
    }

    @PostMapping("/api/hr/cdi-conversions/{id}/decision")
    public CdiConversionDto decide(@PathVariable Long id,
                                   @Valid @RequestBody CdiConversionDecisionRequest dto,
                                   Authentication auth) {
        return service.decide(id, dto, actorId(auth));
    }

    @PostMapping("/api/hr/cdi-conversions/{id}/cancel")
    public CdiConversionDto cancel(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, String> body,
                                   Authentication auth) {
        return service.cancel(id, body != null ? body.get("comment") : null, actorId(auth));
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
