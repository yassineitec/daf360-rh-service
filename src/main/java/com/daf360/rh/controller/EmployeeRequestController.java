package com.daf360.rh.controller;

import com.daf360.rh.common.PageResponse;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.dto.requests.*;
import com.daf360.rh.service.DocumentGenerationService;
import com.daf360.rh.service.EmployeeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hr/requests")
@RequiredArgsConstructor
public class EmployeeRequestController {

    private final EmployeeRequestService   requestService;
    private final DocumentGenerationService docService;

    /**
     * POST /api/hr/requests/{profileId}
     * Employee submits a new request. Validates no duplicate open request of same type.
     */
    @PostMapping("/{profileId}")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<RequestResponseDto> submit(
            @PathVariable Long profileId,
            @Valid @RequestBody RequestSubmitDto dto,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(requestService.submitRequest(profileId, dto, auth));
    }

    /**
     * GET /api/hr/requests?profileId=&status=&typeId=&paysId=&page=0&size=20
     */
    @GetMapping
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<PageResponse<RequestResponseDto>> list(
            @RequestParam(required = false) Long          profileId,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) Long          typeId,
            @RequestParam(required = false) Long          paysId,
            @PageableDefault(size = 20) Pageable          pageable) {

        RequestFilterDto filter = new RequestFilterDto();
        filter.setProfileId(profileId);
        filter.setStatus(status);
        filter.setTypeId(typeId);
        filter.setPaysId(paysId);
        return ResponseEntity.ok(PageResponse.from(requestService.listRequests(filter, pageable)));
    }

    /**
     * GET /api/hr/requests/{id}
     */
    @GetMapping("/{id}")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<RequestResponseDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(requestService.getById(id));
    }

    /**
     * POST /api/hr/requests/{id}/process
     * HR Officer / Finance Officer processes (approve or reject).
     * Required: HR_MANAGER, ADMIN, or FINANCE_OFFICER
     */
    @PostMapping("/{id}/process")
    //@PreAuthorize("hasAnyAuthority('HR_UPDATE_PROFILE', 'HR_ADMIN_ROLES')")
    public ResponseEntity<RequestResponseDto> process(
            @PathVariable Long id,
            @Valid @RequestBody RequestProcessDto dto,
            Authentication auth) {
        return ResponseEntity.ok(requestService.processRequest(id, dto, auth));
    }

    /**
     * POST /api/hr/requests/{id}/cancel?profileId=
     * Employee cancels their own SUBMITTED request.
     */
    @PostMapping("/{id}/cancel")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<RequestResponseDto> cancel(
            @PathVariable Long id,
            @RequestParam Long profileId,
            Authentication auth) {
        return ResponseEntity.ok(requestService.cancelRequest(id, profileId, auth));
    }

    /**
     * GET /api/hr/requests/{id}/document
     * Returns generated documents for an approved DOCUMENT-category request.
     */
    @GetMapping("/{id}/document")
    //@PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<GeneratedDocumentResponseDto>> getDocuments(@PathVariable Long id) {
        return ResponseEntity.ok(docService.listForRequest(id));
    }
}
