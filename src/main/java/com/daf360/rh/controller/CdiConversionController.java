package com.daf360.rh.controller;

import com.daf360.rh.dto.lifecycle.CdiConversionDecisionRequest;
import com.daf360.rh.dto.lifecycle.CdiConversionDto;
import com.daf360.rh.dto.lifecycle.SubmitCdiConversionRequest;
import com.daf360.rh.lifecycle.CdiConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * Files a request on an active CIVP contract, WITH its evaluation file (V120).
     *
     * Multipart and atomic, unlike the leave justificatif's separate call: the file is
     * mandatory here, so a request must never exist without it — and this endpoint has one
     * client, the request modal. Parts: {@code request} (JSON) and {@code evaluation} (file).
     */
    @PostMapping(value = "/api/hr/contracts/{contractId}/cdi-conversion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CdiConversionDto submit(@PathVariable Long contractId,
                                   @Valid @RequestPart("request") SubmitCdiConversionRequest dto,
                                   @RequestPart(value = "evaluation", required = false) MultipartFile evaluation,
                                   Authentication auth) throws IOException {
        return service.submit(contractId, dto, evaluation, auth, actorId(auth));
    }

    /**
     * Streams the evaluation file, {@code inline} with an RFC 5987 filename like the other
     * document downloads. 404 when nothing is attached or the document was deleted.
     */
    @GetMapping("/api/hr/cdi-conversions/{id}/evaluation")
    public ResponseEntity<Resource> evaluation(@PathVariable Long id, Authentication auth) {
        return service.evaluation(id, actorId(auth))
                .map(payload -> {
                    String encoded = URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, payload.contentType())
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                            .body(payload.resource());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
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
