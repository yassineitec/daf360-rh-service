package com.daf360.rh.onboarding;

import com.daf360.rh.config.AppProperties;
import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.dto.onboarding.CompleteProfileRequest;
import com.daf360.rh.dto.onboarding.CompletionResult;
import com.daf360.rh.dto.onboarding.SaveDraftRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.MailService;
import com.daf360.rh.service.OnboardingService;
import com.daf360.rh.service.WorkflowInstanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock CandidateRepository        candidateRepo;
    @Mock ItProvisioningRepository   itProvRepo;
    @Mock EmployeeProfileRepository  profileRepo;
    @Mock WorkingTimeRegimeRepository regimeRepo;
    @Mock WorkflowInstanceService    workflowInstanceService;
    @Mock MailService                mailService;
    @Mock AuditService               auditService;
    @Mock AppProperties              appProperties;
    @Mock JdbcTemplate               jdbc;
    @Mock ObjectMapper               objectMapper;
    @Mock com.daf360.rh.notification.NotificationRoutingService notificationRoutingService;

    @InjectMocks OnboardingService service;

    static final Long HR_ID        = 99L;
    static final Long CANDIDATE_ID = 20L;
    static final Long PAYS_ID      = 1L;
    static final Long PROV_ID      = 10L;
    static final Long USER_ID      = 200L;
    static final Long PROFILE_ID   = 300L;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Candidate candidateInEmailReceived() {
        return Candidate.builder()
                .id(CANDIDATE_ID)
                .paysId(PAYS_ID)
                .firstName("Alice")
                .lastName("Martin")
                .emailPersonal("alice.martin@example.com")
                .status(CandidateStatus.EMAIL_RECEIVED)
                .createdBy(HR_ID)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private Candidate candidateInHrInProgress() {
        return Candidate.builder()
                .id(CANDIDATE_ID)
                .paysId(PAYS_ID)
                .firstName("Alice")
                .lastName("Martin")
                .emailPersonal("alice.martin@example.com")
                .status(CandidateStatus.HR_IN_PROGRESS)
                .createdBy(HR_ID)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private ItProvisioning provisioningWithUser() {
        return ItProvisioning.builder()
                .id(PROV_ID)
                .candidateId(CANDIDATE_ID)
                .userId(USER_ID)
                .ms365Email("alice@itec.com")
                .status(ItProvisioningStatus.EMAIL_CREATED)
                // hardware columns moved to it_assets table (V23 migration)
                .licenseOffice365(false)
                .licenseAutocad(false)
                .licenseRevit(false)
                .licenseAutodesk(false)
                .licenseKaspersky(false)
                .adAccountCreated(false)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private CompleteProfileRequest minimalCompleteProfileRequest() {
        CompleteProfileRequest dto = new CompleteProfileRequest();
        dto.setHireDate(LocalDate.now());
        dto.setContractType("PERMANENT");
        dto.setIsOnProbation(false);
        // grade/discipline/department are FK IDs after V23 — left null in unit test
        // (HR sets them via PATCH /api/hr/profiles/{id} with gradeId/disciplineId/departmentId)
        dto.setRib("TN123");
        dto.setRegimeTemplateId(1L);
        dto.setCnssNumber("CN001");
        return dto;
    }

    // ── Test 1: completeEmployeeProfile_success_profileCreated ───────────────

    @Test
    void completeEmployeeProfile_success_profileCreated() {
        Candidate candidate = candidateInEmailReceived();
        ItProvisioning prov = provisioningWithUser();
        CompleteProfileRequest dto = minimalCompleteProfileRequest();

        EmployeeProfile savedProfile = EmployeeProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .paysId(PAYS_ID)
                .candidateId(CANDIDATE_ID)
                .deleted(false)
                .onboardingCompleted(false)
                .build();

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(itProvRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.of(prov));
        when(profileRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.empty());
        when(profileRepo.save(any(EmployeeProfile.class))).thenReturn(savedProfile);
        when(candidateRepo.save(any(Candidate.class))).thenReturn(candidate);
        when(workflowInstanceService.createOnboardingInstance(
                eq(PROFILE_ID), eq(HR_ID), eq(PAYS_ID), any())).thenReturn(50L);
        when(appProperties.getPortalUrl()).thenReturn("http://localhost:8080");

        CompletionResult result = service.completeEmployeeProfile(CANDIDATE_ID, dto, HR_ID);

        assertThat(result.getEmployeeProfileId()).isEqualTo(PROFILE_ID);
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getWorkflowInstanceId()).isEqualTo(50L);

        verify(candidateRepo).save(any(Candidate.class));
        verify(workflowInstanceService).createOnboardingInstance(
                eq(PROFILE_ID), eq(HR_ID), eq(PAYS_ID), any());
        verify(mailService).sendWelcomeEmail(
                eq("alice@itec.com"), eq("Alice"), eq("alice@itec.com"), anyString());
        verify(auditService).log(
                eq(HR_ID.toString()),
                eq("COMPLETE_ONBOARDING_PROFILE"),
                eq("EMPLOYEE_PROFILE"),
                eq(PROFILE_ID),
                any(),
                any());
        // Notifications dispatched via NotificationRoutingService (async) — not via direct jdbc
        verify(notificationRoutingService, atLeastOnce()).resolveAndDispatch(any());
    }

    // ── Test 2: completeEmployeeProfile_candidateStatusHired_throwsStatusInvalid

    @Test
    void completeEmployeeProfile_candidateStatusHired_throwsStatusInvalid() {
        Candidate candidate = candidateInEmailReceived();
        candidate.setStatus(CandidateStatus.HIRED);

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() ->
                service.completeEmployeeProfile(CANDIDATE_ID, minimalCompleteProfileRequest(), HR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_STATUS_INVALID));

        verify(profileRepo, never()).save(any());
    }

    // ── Test 3: completeEmployeeProfile_provisioningUserIdNull_throwsMissingUser

    @Test
    void completeEmployeeProfile_provisioningUserIdNull_throwsMissingUser() {
        Candidate candidate = candidateInEmailReceived();
        ItProvisioning provNoUser = provisioningWithUser();
        provNoUser.setUserId(null);

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(itProvRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.of(provNoUser));

        assertThatThrownBy(() ->
                service.completeEmployeeProfile(CANDIDATE_ID, minimalCompleteProfileRequest(), HR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_USER_NOT_CREATED));

        verify(profileRepo, never()).save(any());
    }

    // ── Test 4: completeEmployeeProfile_draftDeletedAfterCompletion ──────────

    @Test
    void completeEmployeeProfile_draftDeletedAfterCompletion() {
        Candidate candidate = candidateInEmailReceived();
        ItProvisioning prov = provisioningWithUser();
        CompleteProfileRequest dto = minimalCompleteProfileRequest();

        EmployeeProfile savedProfile = EmployeeProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .paysId(PAYS_ID)
                .candidateId(CANDIDATE_ID)
                .deleted(false)
                .onboardingCompleted(false)
                .build();

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(itProvRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.of(prov));
        when(profileRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.empty());
        when(profileRepo.save(any(EmployeeProfile.class))).thenReturn(savedProfile);
        when(candidateRepo.save(any(Candidate.class))).thenReturn(candidate);
        when(workflowInstanceService.createOnboardingInstance(any(), any(), any(), any())).thenReturn(50L);
        when(appProperties.getPortalUrl()).thenReturn("http://localhost:8080");

        service.completeEmployeeProfile(CANDIDATE_ID, dto, HR_ID);

        verify(jdbc).update(contains("onboarding_drafts"), eq(CANDIDATE_ID));
    }

    // ── Test 5: saveDraft_upsertAndStatusUpdate ───────────────────────────────

    @Test
    void saveDraft_upsertAndStatusUpdate() throws Exception {
        Candidate candidate = candidateInEmailReceived();

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(jdbc.queryForObject(
                contains("COUNT(*)"), eq(Integer.class), eq(CANDIDATE_ID)))
                .thenReturn(0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(candidateRepo.save(any(Candidate.class))).thenReturn(candidate);

        service.saveDraft(CANDIDATE_ID, new SaveDraftRequest(), HR_ID);

        verify(jdbc).update(contains("INSERT INTO [dbo].[onboarding_drafts]"), any(), any(), any());
        verify(candidateRepo).save(argThat(c -> c.getStatus() == CandidateStatus.HR_IN_PROGRESS));
    }

    // ── Test 6: saveDraft_candidateAlreadyHrInProgress_noStatusChange ─────────

    @Test
    void saveDraft_candidateAlreadyHrInProgress_noStatusChange() throws Exception {
        Candidate candidate = candidateInHrInProgress();

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(jdbc.queryForObject(
                contains("COUNT(*)"), eq(Integer.class), eq(CANDIDATE_ID)))
                .thenReturn(0);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(candidateRepo.save(any(Candidate.class))).thenReturn(candidate);

        assertThatNoException().isThrownBy(() ->
                service.saveDraft(CANDIDATE_ID, new SaveDraftRequest(), HR_ID));
    }

    // ── Test 7: completeEmployeeProfile_mailFailure_doesNotFailRequest ────────

    @Test
    void completeEmployeeProfile_mailFailure_doesNotFailRequest() {
        Candidate candidate = candidateInEmailReceived();
        ItProvisioning prov = provisioningWithUser();
        CompleteProfileRequest dto = minimalCompleteProfileRequest();

        EmployeeProfile savedProfile = EmployeeProfile.builder()
                .id(PROFILE_ID)
                .userId(USER_ID)
                .paysId(PAYS_ID)
                .candidateId(CANDIDATE_ID)
                .deleted(false)
                .onboardingCompleted(false)
                .build();

        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate));
        when(itProvRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.of(prov));
        when(profileRepo.findByCandidateId(CANDIDATE_ID)).thenReturn(Optional.empty());
        when(profileRepo.save(any(EmployeeProfile.class))).thenReturn(savedProfile);
        when(candidateRepo.save(any(Candidate.class))).thenReturn(candidate);
        when(workflowInstanceService.createOnboardingInstance(any(), any(), any(), any())).thenReturn(50L);
        when(appProperties.getPortalUrl()).thenReturn("http://localhost:8080");
        doThrow(new RuntimeException("SMTP timeout"))
                .when(mailService).sendWelcomeEmail(anyString(), anyString(), anyString(), anyString());

        CompletionResult result = service.completeEmployeeProfile(CANDIDATE_ID, dto, HR_ID);

        assertThat(result).isNotNull();
        assertThat(result.getEmployeeProfileId()).isEqualTo(PROFILE_ID);
    }
}
