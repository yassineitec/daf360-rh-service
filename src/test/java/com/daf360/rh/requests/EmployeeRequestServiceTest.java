package com.daf360.rh.requests;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRequest;
import com.daf360.rh.domain.RequestApproval;
import com.daf360.rh.domain.RequestTypeCatalog;
import com.daf360.rh.domain.enums.RequestCategory;
import com.daf360.rh.domain.enums.RequestStatus;
import com.daf360.rh.dto.requests.RequestProcessDto;
import com.daf360.rh.dto.requests.RequestResponseDto;
import com.daf360.rh.dto.requests.RequestSubmitDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmployeeRequestService.
 *
 * Scenarios:
 *  1.  Submit — success
 *  2.  Submit — duplicate open request rejected
 *  3.  Process L1-only type → APPROVED directly
 *  4.  Process L1-only type → REJECTED directly
 *  5.  Process L2 type (BANK_DETAILS) — L1 moves to PENDING_L2
 *  6.  Process L2 type — L2 APPROVED sets APPROVED
 *  7.  Process L2 type — L2 REJECTED sets REJECTED
 *  8.  Cancel from SUBMITTED — success
 *  9.  Cancel from IN_REVIEW — blocked
 *  10. Process already APPROVED → wrong-status error
 */
@ExtendWith(MockitoExtension.class)
class EmployeeRequestServiceTest {

    @Mock EmployeeRequestRepository    requestRepo;
    @Mock RequestApprovalRepository    approvalRepo;
    @Mock RequestTypeCatalogRepository typeRepo;
    @Mock EmployeeProfileRepository    profileRepo;
    @Mock DocumentGenerationService    docService;
    @Mock NotificationService          notificationService;
    @Mock AuditService                 auditService;

    @InjectMocks EmployeeRequestService service;

    private static final Long PROFILE_ID  = 1L;
    private static final Long TYPE_L1_ID  = 10L;
    private static final Long TYPE_L2_ID  = 20L;
    private static final Long OFFICER_ID  = 99L;

    // ── Test data builders ────────────────────────────────────────────────────

    private EmployeeProfile profile() {
        EmployeeProfile p = new EmployeeProfile();
        p.setId(PROFILE_ID);
        p.setPaysId(1L);
        p.setUserId(100L);
        return p;
    }

    private RequestTypeCatalog l1Type() {
        return RequestTypeCatalog.builder()
                .id(TYPE_L1_ID).paysId(1L).typeCode("ATTESTATION_TRAVAIL")
                .displayNameFr("Attestation de travail").displayNameEn("Work certificate")
                .category(RequestCategory.DOCUMENT).approvalLevel("L1")
                .defaultSlaDays(2).isActive(true).build();
    }

    private RequestTypeCatalog l2Type() {
        return RequestTypeCatalog.builder()
                .id(TYPE_L2_ID).paysId(1L).typeCode("MISE_A_JOUR_BANCAIRE")
                .displayNameFr("Coordonnées bancaires").displayNameEn("Bank details")
                .category(RequestCategory.BANK_DETAILS).approvalLevel("L2")
                .defaultSlaDays(5).isActive(true).build();
    }

    private EmployeeRequest requestWith(Long typeId, RequestStatus status) {
        EmployeeRequest r = new EmployeeRequest();
        r.setId(42L);
        r.setEmployeeProfileId(PROFILE_ID);
        r.setRequestTypeId(typeId);
        r.setPaysId(1L);
        r.setStatus(status);
        r.setSubmissionDate(OffsetDateTime.now());
        r.setCreatedAt(OffsetDateTime.now());
        return r;
    }

    private RequestProcessDto approveDto() {
        RequestProcessDto d = new RequestProcessDto();
        d.setOfficerId(OFFICER_ID);
        d.setDecision("APPROVED");
        d.setComment("OK");
        return d;
    }

    private RequestProcessDto rejectDto() {
        RequestProcessDto d = new RequestProcessDto();
        d.setOfficerId(OFFICER_ID);
        d.setDecision("REJECTED");
        d.setComment("Non conforme");
        return d;
    }

    // ── 1. Submit — success ────────────────────────────────────────────────────

    @Test
    void submit_success_createsSubmittedRequest() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));
        when(requestRepo.existsByEmployeeProfileIdAndRequestTypeIdAndStatusIn(
                anyLong(), anyLong(), anyList())).thenReturn(false);

        EmployeeRequest saved = requestWith(TYPE_L1_ID, RequestStatus.SUBMITTED);
        when(requestRepo.save(any())).thenReturn(saved);
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestSubmitDto dto = new RequestSubmitDto();
        dto.setTypeId(TYPE_L1_ID);

        RequestResponseDto result = service.submitRequest(PROFILE_ID, dto, null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.SUBMITTED);
        verify(notificationService).sendToHrManager(anyString(), anyString());
        verify(auditService).log(any(), eq("SUBMIT_REQUEST"), any(), any(), any(), any());
    }

    // ── 2. Submit — duplicate ─────────────────────────────────────────────────

    @Test
    void submit_duplicate_throwsConflict() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));
        when(requestRepo.existsByEmployeeProfileIdAndRequestTypeIdAndStatusIn(
                anyLong(), anyLong(), anyList())).thenReturn(true);

        RequestSubmitDto dto = new RequestSubmitDto();
        dto.setTypeId(TYPE_L1_ID);

        assertThatThrownBy(() -> service.submitRequest(PROFILE_ID, dto, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATE);

        verify(requestRepo, never()).save(any());
    }

    // ── 3. Process L1 → APPROVED directly ────────────────────────────────────

    @Test
    void process_l1Type_approved_setsApprovedAndTriggersDocGen() {
        EmployeeRequest req = requestWith(TYPE_L1_ID, RequestStatus.SUBMITTED);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(profile()));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.processRequest(42L, approveDto(), null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.APPROVED);
        verify(docService).generate(any(), any(), any(), any());
        verify(auditService).log(any(), contains("APPROVED"), any(), any(), any(), any());
    }

    // ── 4. Process L1 → REJECTED ──────────────────────────────────────────────

    @Test
    void process_l1Type_rejected_setsRejectedNoPdf() {
        EmployeeRequest req = requestWith(TYPE_L1_ID, RequestStatus.SUBMITTED);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.processRequest(42L, rejectDto(), null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.REJECTED);
        verifyNoInteractions(docService);
    }

    // ── 5. Process L2 — L1 approval sets PENDING_L2 ──────────────────────────

    @Test
    void process_l2Type_l1Approval_setsPendingL2() {
        EmployeeRequest req = requestWith(TYPE_L2_ID, RequestStatus.SUBMITTED);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L2_ID)).thenReturn(Optional.of(l2Type()));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.processRequest(42L, approveDto(), null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.PENDING_L2);
        verify(notificationService).sendToFinance(anyString(), anyString());
        verifyNoInteractions(docService);

        // L1 approval record must be saved
        ArgumentCaptor<RequestApproval> captor = ArgumentCaptor.forClass(RequestApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo("L1");
        assertThat(captor.getValue().getDecision()).isEqualTo("APPROVED");
    }

    // ── 6. Process L2 — L2 APPROVED sets APPROVED ────────────────────────────

    @Test
    void process_l2Type_l2Approval_setsApproved() {
        EmployeeRequest req = requestWith(TYPE_L2_ID, RequestStatus.PENDING_L2);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L2_ID)).thenReturn(Optional.of(l2Type()));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.processRequest(42L, approveDto(), null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.APPROVED);

        ArgumentCaptor<RequestApproval> captor = ArgumentCaptor.forClass(RequestApproval.class);
        verify(approvalRepo).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo("L2");
    }

    // ── 7. Process L2 — L2 REJECTED sets REJECTED ────────────────────────────

    @Test
    void process_l2Type_l2Rejected_setsRejected() {
        EmployeeRequest req = requestWith(TYPE_L2_ID, RequestStatus.PENDING_L2);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L2_ID)).thenReturn(Optional.of(l2Type()));
        when(approvalRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.processRequest(42L, rejectDto(), null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.REJECTED);
        verifyNoInteractions(docService);
    }

    // ── 8. Cancel SUBMITTED — success ────────────────────────────────────────

    @Test
    void cancel_submitted_succeeds() {
        EmployeeRequest req = requestWith(TYPE_L1_ID, RequestStatus.SUBMITTED);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(approvalRepo.findByEmployeeRequestIdOrderByDecisionDate(anyLong()))
                .thenReturn(Collections.emptyList());

        RequestResponseDto result = service.cancelRequest(42L, PROFILE_ID, null);

        assertThat(result.getStatus()).isEqualTo(RequestStatus.CANCELLED);
    }

    // ── 9. Cancel IN_REVIEW — blocked ────────────────────────────────────────

    @Test
    void cancel_inReview_throwsCannotCancel() {
        EmployeeRequest req = requestWith(TYPE_L1_ID, RequestStatus.IN_REVIEW);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.cancelRequest(42L, PROFILE_ID, null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_CANNOT_CANCEL);
    }

    // ── 10. Process already APPROVED → error ─────────────────────────────────

    @Test
    void process_alreadyApproved_throwsWrongStatus() {
        EmployeeRequest req = requestWith(TYPE_L1_ID, RequestStatus.APPROVED);
        when(requestRepo.findById(42L)).thenReturn(Optional.of(req));
        when(typeRepo.findById(TYPE_L1_ID)).thenReturn(Optional.of(l1Type()));

        assertThatThrownBy(() -> service.processRequest(42L, approveDto(), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.REQUEST_WRONG_STATUS);
    }
}
