package com.daf360.rh.controller;

import com.daf360.rh.dto.document.DocumentUploadResponseDto;
import com.daf360.rh.service.EmployeeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/hr/profiles/{profileId}/documents")
@RequiredArgsConstructor
public class EmployeeDocumentController {

    private final EmployeeDocumentService documentService;

    /**
     * GET /api/hr/profiles/{profileId}/documents
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DocumentUploadResponseDto>> list(@PathVariable Long profileId) {
        return ResponseEntity.ok(documentService.listDocuments(profileId));
    }

    /**
     * POST /api/hr/profiles/{profileId}/documents
     * Upload a document (PDF/JPG/PNG, max 10 MB).
     * Required: HR_MANAGER
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<DocumentUploadResponseDto> upload(
            @PathVariable Long profileId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            Authentication auth) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(profileId, file, documentType, auth));
    }

    /**
     * PATCH /api/hr/profiles/{profileId}/documents/{docId}/verify
     * Set document verification status (VERIFIED | REJECTED).
     * Required: HR_MANAGER
     */
    @PatchMapping("/{docId}/verify")
    @PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<DocumentUploadResponseDto> verify(
            @PathVariable Long profileId,
            @PathVariable Long docId,
            @RequestParam String status,
            Authentication auth) {
        return ResponseEntity.ok(documentService.verify(docId, status, auth));
    }
}
