package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.DocumentType;
import com.daf360.rh.dto.DocumentResponseDto;
import com.daf360.rh.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/hr/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("isAuthenticated()")
    public List<DocumentResponseDto> list(@PathVariable Long employeeId) {
        return documentService.findByEmployee(employeeId);
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAnyAuthority('HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<DocumentResponseDto> upload(
            @RequestParam Long employeeId,
            @RequestParam DocumentType documentType,
            @RequestParam(defaultValue = "false") boolean confidential,
            @RequestParam MultipartFile file,
            @AuthenticationPrincipal String actorId) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(employeeId, documentType, confidential, file, actorId));
    }
}
