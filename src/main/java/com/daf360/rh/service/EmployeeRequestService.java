package com.daf360.rh.service;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.RequestApproval;
import com.daf360.rh.domain.RequestTypeCatalog;
import com.daf360.rh.domain.enums.RequestCategory;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.dto.requests.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.pdf.PdfDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages employee self-service requests end-to-end:
 *   submit → process (L1) → [PENDING_L2 → process (L2)] → APPROVED / REJECTED
 *
 * Post-approval actions (triggered on APPROVED):
 *   DOCUMENT            → DocumentGenerationService.generate()
 *   PERSONAL_DATA_CHANGE → field update on EmployeeProfile (attachment_url carries new value JSON)
 *   BANK_DETAILS        → L2 required; on final APPROVED, HR updates profile fields
 *
 * All state transitions are written to audit_log.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeRequestService {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final List<RequestStatus> OPEN_STATUSES =
            List.of(RequestStatus.SUBMITTED, RequestStatus.IN_REVIEW, RequestStatus.PENDING_L2);

    private final EmployeeRequestRepository    requestRepo;
    private final RequestApprovalRepository    approvalRepo;
    private final RequestTypeCatalogRepository typeRepo;
    private final EmployeeProfileRepository    profileRepo;
    private final DocumentGenerationService    docService;
    private final PdfDocumentService           pdfDocumentService;
    private final NotificationService          notificationService;
    private final NotificationRoutingService   notificationRoutingService;
    private final AuditService                 auditService;
    private final JdbcTemplate                 jdbc;

    // ── Submit ────────────────────────────────────────────────────────────────

    /**
     * Employee submits a new request.
     * Rule: only one open request of the same type per employee.
     */
    public RequestResponseDto submitRequest(Long profileId, RequestSubmitDto dto, Authentication auth) {
        EmployeeProfile profile = findProfileOrThrow(profileId);
        RequestTypeCatalog type = findTypeOrThrow(dto.getTypeId());

        // Duplicate guard
        if (requestRepo.existsByEmployeeProfileIdAndRequestTypeIdAndStatusIn(
                profileId, dto.getTypeId(), OPEN_STATUSES)) {
            throw new AppException(ErrorCode.REQUEST_DUPLICATE,
                    "Une demande de type " + type.getTypeCode() + " est déjà en cours");
        }

        OffsetDateTime now = OffsetDateTime.now(PARIS);
        EmployeeRequest request = EmployeeRequest.builder()
                .employeeProfileId(profileId)
                .requestTypeId(type.getId())
                .paysId(profile.getPaysId())
                .submissionDate(now)
                .submissionChannel("WEB")
                .status(RequestStatus.SUBMITTED)
                .attachmentUrl(dto.getAttachmentUrl())
                .closureComment(dto.getComment())
                .createdAt(now)
                .build();

        EmployeeRequest saved = requestRepo.save(request);

        // Notify HR manager
        notificationService.sendToHrManager(
                "Nouvelle demande #" + saved.getId(),
                "Demande " + type.getDisplayNameFr() + " soumise par profileId=" + profileId
                + "\nSLA: " + type.getDefaultSlaDays() + " jour(s)");

        auditService.log(actorId(auth), "SUBMIT_REQUEST", "EmployeeRequest", saved.getId(),
                null, type.getTypeCode());
        return toDto(saved, type);
    }

    // ── Process ───────────────────────────────────────────────────────────────

    /**
     * HR Officer or Finance Officer processes a request.
     *
     * Flow for single-level types (L1):
     *   SUBMITTED → IN_REVIEW → APPROVED / REJECTED
     *
     * Flow for L2 types (BANK_DETAILS):
     *   SUBMITTED → IN_REVIEW → (L1 APPROVED) → PENDING_L2 → (L2 APPROVED) → APPROVED
     *   Any REJECTED at any level → REJECTED immediately
     */
    public RequestResponseDto processRequest(Long requestId, RequestProcessDto dto, Authentication auth) {
        EmployeeRequest request = findRequestOrThrow(requestId);
        RequestTypeCatalog type = findTypeOrThrow(request.getRequestTypeId());

        if (!List.of(RequestStatus.SUBMITTED, RequestStatus.IN_REVIEW, RequestStatus.PENDING_L2)
                .contains(request.getStatus())) {
            throw new AppException(ErrorCode.REQUEST_WRONG_STATUS,
                    "Impossible de traiter une demande au statut " + request.getStatus());
        }

        boolean isL2Type = "L2".equals(type.getApprovalLevel());
        boolean isPendingL2 = request.getStatus() == RequestStatus.PENDING_L2;
        boolean isApproved = "APPROVED".equalsIgnoreCase(dto.getDecision());

        // Determine approval level
        String level = isPendingL2 ? "L2" : "L1";

        // Write approval record
        RequestApproval approval = RequestApproval.builder()
                .employeeRequestId(requestId)
                .level(level)
                .approverId(dto.getOfficerId())
                .decision(dto.getDecision().toUpperCase())
                .comment(dto.getComment())
                .decisionDate(OffsetDateTime.now(PARIS))
                .build();
        approvalRepo.save(approval);

        OffsetDateTime now = OffsetDateTime.now(PARIS);
        String before = request.getStatus().name();

        // Load requesting employee's userId once (used for personal notification)
        EmployeeProfile requester = profileRepo.findById(request.getEmployeeProfileId()).orElse(null);
        Long requesterUserId = requester != null ? requester.getUserId() : null;
        Long requesterPaysId = requester != null ? requester.getPaysId() : null;

        if (!isApproved) {
            // Rejection terminates the flow at any level
            request.setStatus(RequestStatus.REJECTED);
            request.setResolutionDate(now);
            request.setClosureComment(dto.getComment());
            // Notify the requesting employee
            if (requesterUserId != null) {
                try {
                    notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
                            .eventCode("REQUEST_REJECTED")
                            .directUserId(requesterUserId)
                            .paysId(requesterPaysId)
                            .templateVars(java.util.Map.of(
                                    "requestType", type.getDisplayNameFr(),
                                    "comment", dto.getComment() != null ? dto.getComment() : ""
                            ))
                            .build());
                } catch (Exception e) {
                    log.warn("REQUEST_REJECTED notification failed for userId={}: {}", requesterUserId, e.getMessage());
                }
            }
        } else if (isL2Type && !isPendingL2) {
            // L2 type, first approval: move to PENDING_L2
            request.setStatus(RequestStatus.PENDING_L2);
            notificationService.sendToFinance(
                    "Validation L2 requise — demande #" + requestId,
                    "La demande " + type.getDisplayNameFr() + " (BANK_DETAILS) de profileId="
                    + request.getEmployeeProfileId() + " nécessite votre validation.");
        } else {
            // Final approval (L1 non-L2 type OR L2 second approval)
            request.setStatus(RequestStatus.APPROVED);
            request.setResolutionDate(now);
            request.setAssignedOfficerId(dto.getOfficerId());
            executePostApprovalAction(request, type, dto.getOfficerId());
            // Notify the requesting employee that their document is ready
            if (requesterUserId != null) {
                try {
                    notificationRoutingService.resolveAndDispatch(RoutingContext.builder()
                            .eventCode("REQUEST_APPROVED")
                            .directUserId(requesterUserId)
                            .paysId(requesterPaysId)
                            .templateVars(java.util.Map.of(
                                    "requestType", type.getDisplayNameFr()
                            ))
                            .build());
                } catch (Exception e) {
                    log.warn("REQUEST_APPROVED notification failed for userId={}: {}", requesterUserId, e.getMessage());
                }
            }
        }

        request.setUpdatedAt(now);
        EmployeeRequest saved = requestRepo.save(request);

        auditService.log(actorId(auth), "PROCESS_REQUEST_" + dto.getDecision().toUpperCase(),
                "EmployeeRequest", requestId, before, saved.getStatus().name());
        return toDto(saved, type);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    public RequestResponseDto cancelRequest(Long requestId, Long profileId, Authentication auth) {
        EmployeeRequest request = findRequestOrThrow(requestId);

        if (!request.getEmployeeProfileId().equals(profileId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Vous ne pouvez annuler que vos propres demandes");
        }
        if (request.getStatus() != RequestStatus.SUBMITTED) {
            throw new AppException(ErrorCode.REQUEST_CANNOT_CANCEL);
        }

        request.setStatus(RequestStatus.CANCELLED);
        request.setUpdatedAt(OffsetDateTime.now(PARIS));
        EmployeeRequest saved = requestRepo.save(request);

        auditService.log(actorId(auth), "CANCEL_REQUEST", "EmployeeRequest", requestId,
                RequestStatus.SUBMITTED.name(), RequestStatus.CANCELLED.name());
        return toDto(saved, findTypeOrThrow(request.getRequestTypeId()));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<RequestResponseDto> listRequests(RequestFilterDto filter, Pageable pageable) {
        if (filter.getProfileId() != null && filter.getStatus() != null) {
            return requestRepo.findByEmployeeProfileIdAndStatusOrderByCreatedAtDesc(
                    filter.getProfileId(), filter.getStatus(), pageable)
                    .map(r -> toDto(r, safeType(r.getRequestTypeId())));
        }
        if (filter.getProfileId() != null) {
            return requestRepo.findByEmployeeProfileIdOrderByCreatedAtDesc(
                    filter.getProfileId(), pageable)
                    .map(r -> toDto(r, safeType(r.getRequestTypeId())));
        }
        if (filter.getPaysId() != null && filter.getStatus() != null) {
            return requestRepo.findByPaysIdAndStatusOrderByCreatedAtDesc(
                    filter.getPaysId(), filter.getStatus(), pageable)
                    .map(r -> toDto(r, safeType(r.getRequestTypeId())));
        }
        return requestRepo.findAll(pageable).map(r -> toDto(r, safeType(r.getRequestTypeId())));
    }

    @Transactional(readOnly = true)
    public RequestResponseDto getById(Long id) {
        EmployeeRequest r = findRequestOrThrow(id);
        return toDto(r, safeType(r.getRequestTypeId()));
    }

    // ── Post-approval actions ─────────────────────────────────────────────────

    private void executePostApprovalAction(EmployeeRequest request, RequestTypeCatalog type, Long actorId) {
        if (type.getCategory() == RequestCategory.DOCUMENT) {
            Long profileId = request.getEmployeeProfileId();
            Long requestId = request.getId();
            // Route attestation types to the new PDF service; fall back to legacy for others
            switch (type.getTypeCode()) {
                case "ATTESTATION_TRAVAIL" -> {
                    try { pdfDocumentService.generateAttestationTravailPdf(profileId, requestId, actorId); }
                    catch (Exception e) { log.warn("Attestation travail PDF failed for request {}: {}", requestId, e.getMessage()); }
                }
                case "ATTESTATION_SALAIRE" -> {
                    try { pdfDocumentService.generateAttestationSalairePdf(profileId, requestId, actorId); }
                    catch (Exception e) { log.warn("Attestation salaire PDF skipped for request {}: {}", requestId, e.getMessage()); }
                }
                case "ATTESTATION_NON_BENEFICE_PRET" -> {
                    try { pdfDocumentService.generateAttestationNonBeneficePretPdf(profileId, requestId, actorId); }
                    catch (Exception e) { log.warn("Attestation pret PDF failed for request {}: {}", requestId, e.getMessage()); }
                }
                case "ATTESTATION_TITULARISATION" -> {
                    try { pdfDocumentService.generateAttestationTitularisationPdf(profileId, requestId, actorId); }
                    catch (Exception e) { log.warn("Attestation titularisation PDF skipped for request {}: {}", requestId, e.getMessage()); }
                }
                case "ATTESTATION_DOMICILIATION_SALAIRE" -> {
                    try { pdfDocumentService.generateAttestationDomiciliationSalairePdf(profileId, requestId, actorId); }
                    catch (Exception e) { log.warn("Attestation domiciliation PDF skipped for request {}: {}", requestId, e.getMessage()); }
                }
                default -> {
                    // Legacy PDFBox generation for other document types
                    EmployeeProfile profile = findProfileOrThrow(profileId);
                    docService.generate(request, type, profile, actorId);
                }
            }
        } else if (type.getCategory() == RequestCategory.PERSONAL_DATA_CHANGE) {
            log.info("Personal data change approved for requestId={} — manual follow-up required", request.getId());
        } else if (type.getCategory() == RequestCategory.BANK_DETAILS) {
            log.info("Bank details update approved (L2) for requestId={} — HR to update profile", request.getId());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EmployeeRequest findRequestOrThrow(Long id) {
        return requestRepo.findById(id).orElseThrow(() ->
                new AppException(ErrorCode.REQUEST_NOT_FOUND, "Demande introuvable: id=" + id));
    }

    private EmployeeProfile findProfileOrThrow(Long profileId) {
        return profileRepo.findById(profileId).orElseThrow(() ->
                new AppException(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    private RequestTypeCatalog findTypeOrThrow(Long typeId) {
        return typeRepo.findById(typeId).orElseThrow(() ->
                new AppException(ErrorCode.REQUEST_TYPE_NOT_FOUND));
    }

    private RequestTypeCatalog safeType(Long typeId) {
        return typeRepo.findById(typeId).orElse(null);
    }

    RequestResponseDto toDto(EmployeeRequest r, RequestTypeCatalog type) {
        RequestResponseDto dto = new RequestResponseDto();
        dto.setId(r.getId());
        dto.setEmployeeProfileId(r.getEmployeeProfileId());
        dto.setRequestTypeId(r.getRequestTypeId());
        if (type != null) {
            dto.setTypeCode(type.getTypeCode());
            dto.setTypeDisplayNameFr(type.getDisplayNameFr());
        }
        dto.setEmployeeName(resolveEmployeeName(r.getEmployeeProfileId()));
        dto.setPaysId(r.getPaysId());
        dto.setSubmissionDate(r.getSubmissionDate());
        dto.setSubmissionChannel(r.getSubmissionChannel());
        dto.setStatus(r.getStatus());
        dto.setAssignedOfficerId(r.getAssignedOfficerId());
        dto.setResolutionDate(r.getResolutionDate());
        dto.setClosureComment(r.getClosureComment());
        dto.setAttachmentUrl(r.getAttachmentUrl());
        dto.setCreatedAt(r.getCreatedAt());
        dto.setApprovals(
                approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(r.getId())
                        .stream().map(a -> {
                            ApprovalSummaryDto ad = new ApprovalSummaryDto();
                            ad.setId(a.getId());
                            ad.setLevel(a.getLevel());
                            ad.setApproverId(a.getApproverId());
                            ad.setDecision(a.getDecision());
                            ad.setComment(a.getComment());
                            ad.setDecisionDate(a.getDecisionDate());
                            return ad;
                        }).collect(Collectors.toList()));
        return dto;
    }

    private String actorId(Authentication auth) {
        return auth != null && auth.getPrincipal() != null
                ? auth.getPrincipal().toString() : "SYSTEM";
    }

    /** Resolves the requesting employee's display name via profile → Users. Null-safe. */
    private String resolveEmployeeName(Long profileId) {
        if (profileId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT COALESCE(u.fullName, u.username, u.email) " +
                    "FROM [dbo].[employee_profiles] ep " +
                    "JOIN [dbo].[Users] u ON u.id = ep.user_id " +
                    "WHERE ep.id = ?",
                    String.class, profileId);
        } catch (Exception e) {
            return null;
        }
    }
}
