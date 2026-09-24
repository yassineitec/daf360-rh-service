package com.daf360.rh.controller;

import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.document.DocumentHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * The employee's whole SharePoint dossier — the profile page's "Historique documents" tab.
 *
 * <p>Its own controller rather than new methods on {@code EmployeeDocumentController}: same URL
 * prefix, but a different authorisation. The dossier includes the payroll tree (every payslip
 * and salary certificate), so it is gated on the HR-manager / admin authorities — exactly the
 * set behind the frontend's {@code canViewSensitive} — and NOT on {@code isAuthenticated()}.
 *
 * <p>No new permission code on purpose: the JWT permissions claim is already near the browser's
 * 4 KB cookie ceiling, and one more code can push some users into a login loop.
 */
@RestController
@RequestMapping("/api/hr/profiles/{profileId}/documents/history")
@RequiredArgsConstructor
public class DocumentHistoryController {

    /** Mirrors the rh-frontend UserStore HR_MANAGER_PERMS + ADMIN_PERMS. Keep the two in step. */
    private static final String SENSITIVE_READ =
            "hasAnyAuthority('HR_CREATE_PROFILE', 'HR_UPDATE_PROFILE', 'HR_ARCHIVE_PROFILE', "
            + "'HR_ONBOARDING', 'SETTLE_LEAVES', 'RESPONSE_LEAVE', "
            + "'CREATE_ROLE', 'UPDATE_ROLE', 'DELETE_ROLE', 'HR_ADMIN_ROLES', 'ADMIN_ROLES')";

    private final DocumentHistoryService historyService;
    private final AuditService           auditService;

    /**
     * Every file in the employee's SharePoint folders, newest first.
     *
     * @param refresh bypass the per-profile cache (the tab's refresh button)
     */
    @GetMapping
    @PreAuthorize(SENSITIVE_READ)
    public ResponseEntity<DocumentHistoryService.History> history(
            @PathVariable Long profileId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(historyService.history(profileId, refresh));
    }

    /**
     * One file's bytes. {@code itemId} is a handle from the listing above, never a path — the
     * service refuses any id that is not in this profile's own dossier.
     */
    @GetMapping("/{itemId}/content")
    @PreAuthorize(SENSITIVE_READ)
    public ResponseEntity<Resource> content(@PathVariable Long profileId,
                                            @PathVariable String itemId,
                                            Authentication auth) {
        DocumentHistoryService.FilePayload file = historyService.download(profileId, itemId);

        // Audited: this reaches payslips and ID scans, and "who opened what" is the question
        // asked after the fact.
        auditService.log(auth != null && auth.getPrincipal() != null ? auth.getPrincipal().toString() : "SYSTEM",
                "VIEW_SHAREPOINT_DOCUMENT", "EmployeeProfile", profileId, null, file.fileName());

        String encoded = java.net.URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(new ByteArrayResource(file.bytes()));
    }
}
