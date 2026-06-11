package com.daf360.rh.candidate;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.ItProvisioning;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.ItProvisioningStatus;
import com.daf360.rh.dto.candidate.*;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.CandidateMapper;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.ItProvisioningRepository;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.CandidateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateServiceTest {

    @Mock CandidateRepository      candidateRepo;
    @Mock ItProvisioningRepository itProvRepo;
    @Mock CandidateMapper          mapper;
    @Mock AuditService             auditService;
    @Mock JdbcTemplate             jdbc;

    @InjectMocks CandidateService service;

    private static final Long ACTOR_ID = 42L;
    private static final Long PAYS_ID  = 1L;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Candidate pendingCandidate(Long id) {
        return Candidate.builder()
                .id(id)
                .firstName("Alice")
                .lastName("Martin")
                .emailPersonal("alice.martin@example.com")
                .paysId(PAYS_ID)
                .status(CandidateStatus.PENDING)
                .createdBy(ACTOR_ID)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    private CandidateResponse stubResponse(Candidate c) {
        CandidateResponse r = new CandidateResponse();
        r.setId(c.getId());
        r.setStatus(c.getStatus());
        r.setEmailPersonal(c.getEmailPersonal());
        return r;
    }

    @BeforeEach
    void stubItProvRepo() {
        // By default no provisioning exists for any candidate
        when(itProvRepo.findByCandidateId(anyLong())).thenReturn(Optional.empty());
    }

    // ── createCandidate ───────────────────────────────────────────────────────

    @Test
    void createCandidate_success() {
        CreateCandidateRequest req = new CreateCandidateRequest();
        req.setFirstName("Alice");
        req.setLastName("Martin");
        req.setEmailPersonal("alice.martin@example.com");
        req.setPaysId(PAYS_ID);

        Candidate entity = pendingCandidate(null);
        Candidate saved  = pendingCandidate(1L);

        when(candidateRepo.existsByEmailPersonal(req.getEmailPersonal())).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(entity);
        when(candidateRepo.save(entity)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(stubResponse(saved));

        CandidateResponse result = service.createCandidate(req, ACTOR_ID);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getStatus()).isEqualTo(CandidateStatus.PENDING);
        verify(candidateRepo).save(entity);
        verify(auditService).log(eq(ACTOR_ID.toString()), eq("CREATE"), eq("CANDIDATE"),
                eq(1L), isNull(), anyString());
    }

    @Test
    void createCandidate_duplicateEmail_throwsConflict() {
        CreateCandidateRequest req = new CreateCandidateRequest();
        req.setEmailPersonal("duplicate@example.com");
        req.setPaysId(PAYS_ID);

        when(candidateRepo.existsByEmailPersonal("duplicate@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createCandidate(req, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CANDIDATE_EMAIL_DUPLICATE));

        verify(candidateRepo, never()).save(any());
    }

    // ── acceptCandidate ───────────────────────────────────────────────────────

    @Test
    void acceptCandidate_pendingCandidate_createsProvisioningAndNotifies() {
        Candidate candidate = pendingCandidate(10L);
        when(candidateRepo.findById(10L)).thenReturn(Optional.of(candidate));
        when(candidateRepo.save(candidate)).thenReturn(candidate);
        when(mapper.toResponse(candidate)).thenReturn(stubResponse(candidate));

        // No IT users in this pays (simplifies notification assertion)
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("IT_PROVISIONING"), eq(PAYS_ID)))
                .thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("HR_ONBOARDING"), eq(PAYS_ID)))
                .thenReturn(List.of());

        service.acceptCandidate(10L, ACTOR_ID);

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.ACCEPTED);
        assertThat(candidate.getAcceptedBy()).isEqualTo(ACTOR_ID);
        assertThat(candidate.getAcceptedAt()).isNotNull();

        // IT provisioning row created
        ArgumentCaptor<ItProvisioning> provCaptor = ArgumentCaptor.forClass(ItProvisioning.class);
        verify(itProvRepo).save(provCaptor.capture());
        assertThat(provCaptor.getValue().getCandidateId()).isEqualTo(10L);
        assertThat(provCaptor.getValue().getStatus()).isEqualTo(ItProvisioningStatus.PENDING);

        verify(auditService).log(eq(ACTOR_ID.toString()), eq("ACCEPT"), eq("CANDIDATE"),
                eq(10L), anyString(), anyString());
    }

    @Test
    void acceptCandidate_notPending_throwsStatusInvalid() {
        Candidate candidate = pendingCandidate(10L);
        candidate.setStatus(CandidateStatus.ACCEPTED);

        when(candidateRepo.findById(10L)).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.acceptCandidate(10L, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CANDIDATE_STATUS_INVALID));

        verify(candidateRepo, never()).save(any());
        verify(itProvRepo,    never()).save(any());
    }

    @Test
    void acceptCandidate_notFound_throwsNotFound() {
        when(candidateRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptCandidate(99L, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CANDIDATE_NOT_FOUND));
    }

    // ── rejectCandidate ───────────────────────────────────────────────────────

    @Test
    void rejectCandidate_pendingCandidate_setsStatusAndReason() {
        Candidate candidate = pendingCandidate(10L);
        when(candidateRepo.findById(10L)).thenReturn(Optional.of(candidate));
        when(candidateRepo.save(candidate)).thenReturn(candidate);
        when(mapper.toResponse(candidate)).thenReturn(stubResponse(candidate));

        RejectCandidateRequest req = new RejectCandidateRequest();
        req.setRejectionReason("Profil ne correspond pas au poste");

        service.rejectCandidate(10L, req, ACTOR_ID);

        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.REJECTED);
        assertThat(candidate.getRejectionReason()).isEqualTo("Profil ne correspond pas au poste");

        verify(auditService).log(eq(ACTOR_ID.toString()), eq("REJECT"), eq("CANDIDATE"),
                eq(10L), anyString(), contains("REJECTED"));
    }

    @Test
    void rejectCandidate_notPending_throwsStatusInvalid() {
        Candidate candidate = pendingCandidate(10L);
        candidate.setStatus(CandidateStatus.REJECTED);

        when(candidateRepo.findById(10L)).thenReturn(Optional.of(candidate));

        RejectCandidateRequest req = new RejectCandidateRequest();
        req.setRejectionReason("Déjà rejeté");

        assertThatThrownBy(() -> service.rejectCandidate(10L, req, ACTOR_ID))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.CANDIDATE_STATUS_INVALID));

        verify(candidateRepo, never()).save(any());
    }

    @Test
    void acceptCandidate_notifiesSpecificUsers() {
        Candidate candidate = pendingCandidate(10L);
        when(candidateRepo.findById(10L)).thenReturn(Optional.of(candidate));
        when(candidateRepo.save(candidate)).thenReturn(candidate);
        when(mapper.toResponse(candidate)).thenReturn(stubResponse(candidate));

        when(jdbc.queryForList(anyString(), eq(Long.class), eq("IT_PROVISIONING"), eq(PAYS_ID)))
                .thenReturn(List.of(101L, 102L));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("HR_ONBOARDING"), eq(PAYS_ID)))
                .thenReturn(List.of(201L));

        service.acceptCandidate(10L, ACTOR_ID);

        // 2 IT notifications + 1 HR notification
        verify(jdbc, times(3)).update(anyString(), anyLong(), anyString(), anyString());
    }
}
