package com.daf360.rh.controller;

import com.daf360.rh.dto.document.DocumentMetadataRequest;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.service.EmployeeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/hr/profiles/{profileId}/documents")
@RequiredArgsConstructor
public class EmployeeDocumentController {

    private static final String WRITE_AUTH =
            "hasAnyAuthority('HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES', 'ADMIN_ROLES')";

    private final EmployeeDocumentService documentService;

    /** Live documents only — soft-deleted rows are in {@link #listDeleted}. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DocumentUploadResponseDto>> list(@PathVariable Long profileId) {
        return ResponseEntity.ok(documentService.listDocuments(profileId));
    }

    /** The corbeille: what was withdrawn from the dossier, when and by whom. */
    @GetMapping("/deleted")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<List<DocumentUploadResponseDto>> listDeleted(@PathVariable Long profileId) {
        return ResponseEntity.ok(documentService.listDeletedDocuments(profileId));
    }

    /**
     * Upload a document (PDF/JPG/PNG, max 10 MB).
     *
     * `expirationDate` and `notes` are optional and map to columns that existed from the
     * start but were never exposed — an expiring CIN or titre de séjour is the main thing
     * an HR document list needs to warn about.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<DocumentUploadResponseDto> upload(
            @PathVariable Long profileId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            @RequestParam(value = "expirationDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(value = "notes", required = false) String notes,
            Authentication auth) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(profileId, file, documentType,
                        expirationDate, notes, auth));
    }

    /**
     * Streams the stored file. The ONLY way to open a document: `file_url` is a path on the
     * service's disk and nothing serves the upload directory statically, so before this
     * endpoint a document could be uploaded and never read back.
     *
     * `inline` so a PDF or an image opens in the browser's own viewer; the filename is
     * RFC 5987-encoded because uploaded names carry accents.
     */
    @GetMapping("/{docId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable Long profileId,
                                             @PathVariable Long docId) {
        EmployeeDocumentService.DownloadPayload payload = documentService.download(profileId, docId);
        String encoded = java.net.URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, payload.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                .body(payload.resource());
    }

    /** Set document verification status (VERIFIED | REJECTED). */
    @PatchMapping("/{docId}/verify")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES', 'ADMIN_ROLES')")
    public ResponseEntity<DocumentUploadResponseDto> verify(
            @PathVariable Long profileId,
            @PathVariable Long docId,
            @RequestParam String status,
            Authentication auth) {
        return ResponseEntity.ok(documentService.verify(docId, status, auth));
    }

    /** Corrects the type, the expiry or the note. The file is never replaced. */
    @PatchMapping("/{docId}")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<DocumentUploadResponseDto> updateMetadata(
            @PathVariable Long profileId,
            @PathVariable Long docId,
            @RequestBody DocumentMetadataRequest req,
            Authentication auth) {
        return ResponseEntity.ok(documentService.updateMetadata(docId, req, auth));
    }

    /**
     * Withdraws the document — soft delete (V77). The row and the file both stay: the dossier
     * must be able to show that a pièce was filed and later removed, and by whom.
     */
    @DeleteMapping("/{docId}")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<Void> delete(@PathVariable Long profileId,
                                       @PathVariable Long docId,
                                       Authentication auth) {
        documentService.softDelete(profileId, docId, auth);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{docId}/restore")
    @PreAuthorize(WRITE_AUTH)
    public ResponseEntity<DocumentUploadResponseDto> restore(@PathVariable Long profileId,
                                                             @PathVariable Long docId,
                                                             Authentication auth) {
        return ResponseEntity.ok(documentService.restore(profileId, docId, auth));
    }
}
