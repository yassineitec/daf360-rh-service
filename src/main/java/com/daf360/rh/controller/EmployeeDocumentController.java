package com.daf360.rh.controller;

import com.daf360.rh.dto.document.DocumentMetadataRequest;
import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.service.EmployeeDocumentService;
import com.daf360.rh.service.document.DocumentTypeService;
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

    /**
     * The types this employee's COUNTRY accepts, in display order, with their labels.
     *
     * <p>Scoped to the profile rather than taking a {@code paysId}: the caller is already on a
     * profile page and must not be able to offer a type that country does not have — the
     * vocabulary is per-country ({@code document_types}, V87) precisely because CNSS has no
     * Egyptian equivalent.
     *
     * <p>Labels come from the DB, not from {@code PROFILES.DOC_TYPES.*}: an i18n key would put
     * a frontend deploy back in the path of adding a type, which is the whole thing V87
     * removes. The frontend falls back to the code when a label is missing.
     */
    @GetMapping("/types")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DocumentTypeService.DocumentType>> types(@PathVariable Long profileId) {
        return ResponseEntity.ok(documentService.typesForProfile(profileId));
    }

    /** Live documents only — soft-deleted rows are in {@link #listDeleted}. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DocumentUploadResponseDto>> list(@PathVariable Long profileId) {
        return ResponseEntity.ok(documentService.listDocuments(profileId));
    }

    /**
     * What is actually in the employee's SharePoint folder for one type.
     *
     * <p>Lazily, per type: the caller expands a section and pays one Graph round trip for it.
     * Listing every configured folder on tab open would be a round trip per type per profile
     * view, which is how a directory page earns a 429.
     *
     * <p>Answers an empty list rather than an error when SharePoint is unconfigured or the folder
     * is absent — the locally-known documents must stay visible either way.
     */
    @GetMapping("/remote")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EmployeeDocumentService.RemoteDocument>> listRemote(
            @PathVariable Long profileId,
            @RequestParam String type) {
        return ResponseEntity.ok(documentService.listRemote(profileId, type));
    }

    /**
     * Streams one file straight out of SharePoint.
     *
     * <p>Takes a TYPE and a NAME, never a path: the folder is derived server-side from the
     * profile and the type. This endpoint can reach a site holding every employee's contracts
     * and identity documents, so accepting a path would be a traversal hole over precisely the
     * data that must not leak.
     */
    @GetMapping("/remote/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> downloadRemote(@PathVariable Long profileId,
                                                   @RequestParam String type,
                                                   @RequestParam String name) {
        EmployeeDocumentService.DownloadPayload payload =
                documentService.downloadRemote(profileId, type, name);
        String encoded = java.net.URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, payload.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(payload.resource());
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
