package com.daf360.rh.controller;

import com.daf360.rh.dto.pdf.GeneratedDocumentResponse;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.service.pdf.PdfDocumentService;
import com.daf360.rh.service.pdf.PdfGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/hr/documents")
@RequiredArgsConstructor
public class PdfDocumentController {

    private final PdfDocumentService pdfService;

    // -------------------------------------------------------------------------
    // Functional interface for uniform error handling
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface PdfSupplier {
        GeneratedDocumentResponse get();
    }

    private ResponseEntity<Object> generateAndReturn(PdfSupplier supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (AppException e) {
            log.warn("PDF generation business rule violation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(Map.of("error", e.getMessage()));
        } catch (PdfGenerationException e) {
            log.error("PDF service error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    @PostMapping("/decharge-responsabilite")
    @PreAuthorize("hasAnyAuthority('IT_PROVISIONING', 'HR_ONBOARDING')")
    public ResponseEntity<Object> generateDecharge(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long candidateId       = toLong(body.get("candidateId"));
        Long itProvisioningId  = toLong(body.get("itProvisioningId"));
        String context         = (String) body.get("context");
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateDechargePdf(candidateId, itProvisioningId, context, actorId));
    }

    @PostMapping("/attestation-travail")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ONBOARDING')")
    public ResponseEntity<Object> generateAttestationTravail(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long employeeProfileId = toLong(body.get("employeeProfileId"));
        Long requestId         = toLong(body.get("requestId"));
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateAttestationTravailPdf(employeeProfileId, requestId, actorId));
    }

    @PostMapping("/attestation-salaire")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<Object> generateAttestationSalaire(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long employeeProfileId = toLong(body.get("employeeProfileId"));
        Long requestId         = toLong(body.get("requestId"));
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateAttestationSalairePdf(employeeProfileId, requestId, actorId));
    }

    @PostMapping("/attestation-non-benefice-pret")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<Object> generateAttestationNonBeneficePret(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long employeeProfileId = toLong(body.get("employeeProfileId"));
        Long requestId         = toLong(body.get("requestId"));
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateAttestationNonBeneficePretPdf(employeeProfileId, requestId, actorId));
    }

    @PostMapping("/attestation-titularisation")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<Object> generateAttestationTitularisation(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long employeeProfileId = toLong(body.get("employeeProfileId"));
        Long requestId         = toLong(body.get("requestId"));
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateAttestationTitularisationPdf(employeeProfileId, requestId, actorId));
    }

    @PostMapping("/attestation-domiciliation-salaire")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<Object> generateAttestationDomiciliationSalaire(
            @RequestBody Map<String, Object> body,
            Authentication auth) {
        Long employeeProfileId = toLong(body.get("employeeProfileId"));
        Long requestId         = toLong(body.get("requestId"));
        Long actorId           = actorId(auth);
        return generateAndReturn(() ->
                pdfService.generateAttestationDomiciliationSalairePdf(employeeProfileId, requestId, actorId));
    }

    @GetMapping("/by-request/{requestId}")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ONBOARDING')")
    public ResponseEntity<GeneratedDocumentResponse> getByRequest(@PathVariable Long requestId) {
        return pdfService.findByRequest(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-profile/{profileId}")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE')")
    public ResponseEntity<List<GeneratedDocumentResponse>> getByProfile(@PathVariable Long profileId) {
        return ResponseEntity.ok(pdfService.listByProfile(profileId));
    }

    @GetMapping("/download/{id}")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ONBOARDING')")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        try {
            byte[] bytes = pdfService.downloadDocument(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"document-" + id + ".pdf\"")
                    .body(bytes);
        } catch (AppException e) {
            return ResponseEntity.notFound().build();
        } catch (PdfGenerationException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.parseLong(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
