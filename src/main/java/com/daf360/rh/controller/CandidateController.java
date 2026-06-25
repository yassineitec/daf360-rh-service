package com.daf360.rh.controller;

import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.dto.candidate.*;
import com.daf360.rh.service.CandidateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;

@RestController
@RequestMapping("/api/hr/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateService candidateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse create(@Valid @RequestBody CreateCandidateRequest request,
                                    Authentication auth) {
        return candidateService.createCandidate(request, actorId(auth));
    }

    @GetMapping
    //@PreAuthorize("hasAnyAuthority('VIEW_CANDIDATES','HR_ONBOARDING','IT_PROVISIONING','ADMIN_ROLES')")
    public Page<CandidateListItem> list(
            @RequestParam(required = false) CandidateStatus status,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long paysId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 12) Pageable pageable) {
        return candidateService.listCandidates(status, stage, paysId, search, pageable);
    }

    @GetMapping("/{id}")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING') or hasPermission(null, 'IT_PROVISIONING')")
    public CandidateResponse get(@PathVariable Long id) {
        return candidateService.getCandidate(id);
    }

    @PutMapping("/{id}")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse update(@PathVariable Long id,
                                    @Valid @RequestBody UpdateCandidateRequest request,
                                    Authentication auth) {
        return candidateService.updateCandidate(id, request, actorId(auth));
    }

    @PostMapping("/{id}/accept")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse accept(@PathVariable Long id, Authentication auth) {
        return candidateService.acceptCandidate(id, actorId(auth));
    }

    @PostMapping("/{id}/reject")
    //@PreAuthorize("hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse reject(@PathVariable Long id,
                                    @Valid @RequestBody RejectCandidateRequest request,
                                    Authentication auth) {
        return candidateService.rejectCandidate(id, request, actorId(auth));
    }

    /** Upload CV (PDF / DOC / DOCX, max 10 MB). Replaces any previous file. */
    @PostMapping(value = "/{id}/cv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    //@PreAuthorize("hasPermission(null, 'EDIT_CANDIDATE') or hasPermission(null, 'HR_ONBOARDING')")
    public CandidateResponse uploadCv(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      Authentication auth) {
        return candidateService.uploadCv(id, file, actorId(auth));
    }

    /** Download the candidate's CV file. */
    @GetMapping("/{id}/cv")
    //@PreAuthorize("hasPermission(null, 'VIEW_CANDIDATES') or hasPermission(null, 'HR_ONBOARDING')")
    public ResponseEntity<Resource> downloadCv(@PathVariable Long id) {
        Resource resource = candidateService.downloadCv(id);
        String filename  = resource.getFilename() != null ? resource.getFilename() : "cv";
        // Strip UUID prefix (uuid_originalname.pdf → originalname.pdf)
        String displayName = filename.contains("_") ? filename.substring(filename.indexOf('_') + 1) : filename;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(displayName).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/{id}/hire")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'RH_HIRE_CANDIDATE')")
    public HireCandidateResponse hire(@PathVariable Long id,
                                      @Valid @RequestBody HireCandidateRequest request,
                                      Authentication auth) {
        return candidateService.hireCandidate(id, request, actorId(auth));
    }

    @GetMapping("/{id}/history")
    //@PreAuthorize("hasAnyAuthority('VIEW_CANDIDATES','HR_ONBOARDING','IT_PROVISIONING','HR_UPDATE_PROFILE','ADMIN_ROLES')")
    public ResponseEntity<List<CandidateHistoryItem>> getHistory(
            @PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(candidateService.getHistory(id));
    }

    /** JWT sub = String.valueOf(userId) — see portal JwtTokenService. */
    private Long actorId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return 1L;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }
}
