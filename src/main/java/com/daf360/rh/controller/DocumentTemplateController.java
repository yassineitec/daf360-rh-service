package com.daf360.rh.controller;

import com.daf360.rh.common.DocumentVariableCatalog;
import com.daf360.rh.dto.document.*;
import com.daf360.rh.service.DocumentTemplateService;
import com.daf360.rh.service.pdf.PdfGenerationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class DocumentTemplateController {

    private final DocumentTemplateService svc;

    // ── Catalog ───────────────────────────────────────────────────────────────

    @GetMapping("/api/hr/admin/document-templates/variables")
    public List<DocumentVariableCatalog.VariableDef> getVariables() {
        return svc.getVariableCatalog();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/hr/admin/document-templates")
    public List<DocumentTemplateDto> list(
            @RequestParam Long    paysId,
            @RequestParam(required = false) String  category,
            @RequestParam(defaultValue = "false")   boolean includeInactive) {
        return svc.list(paysId, category, includeInactive);
    }

    @GetMapping("/api/hr/admin/document-templates/{id}")
    public DocumentTemplateDto getById(@PathVariable Long id) {
        return svc.getById(id);
    }

    @PostMapping("/api/hr/admin/document-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentTemplateDto create(
            @RequestBody @Valid SaveDocumentTemplateDto dto,
            Authentication auth) {
        return svc.create(dto, actorId(auth));
    }

    @PutMapping("/api/hr/admin/document-templates/{id}")
    public DocumentTemplateDto update(
            @PathVariable Long id,
            @RequestBody @Valid SaveDocumentTemplateDto dto) {
        return svc.update(id, dto);
    }

    @PatchMapping("/api/hr/admin/document-templates/{id}/toggle-active")
    public DocumentTemplateDto toggleActive(@PathVariable Long id) {
        return svc.toggleActive(id);
    }

    @DeleteMapping("/api/hr/admin/document-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        svc.delete(id);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @PostMapping("/api/hr/admin/document-templates/{id}/render")
    public ResponseEntity<byte[]> render(
            @PathVariable Long id,
            @RequestBody RenderTemplateRequest req) {
        try {
            byte[]      pdf  = svc.render(id, req.getEmployeeProfileId());
            DocumentTemplateDto tmpl = svc.getById(id);
            String filename = tmpl.getName().replaceAll("[^a-zA-Z0-9\\-_]", "_") + ".pdf";
            return pdfResponse(pdf, filename);
        } catch (PdfGenerationException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PostMapping("/api/hr/admin/document-templates/preview-raw")
    public ResponseEntity<byte[]> previewRaw(@RequestBody @Valid PreviewRawRequest req) {
        try {
            byte[] pdf = svc.previewRaw(req.getHtmlContent(), req.getPaysId(), req.getEmployeeProfileId());
            return pdfResponse(pdf, "apercu.pdf");
        } catch (PdfGenerationException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    private Long actorId(Authentication auth) {
        if (auth == null) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
