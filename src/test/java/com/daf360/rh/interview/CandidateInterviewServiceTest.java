package com.daf360.rh.interview;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.UpdateInterviewRequest;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.CandidateRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.CandidateInterviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateInterviewServiceTest {

    @Mock CandidateInterviewRepository interviewRepo;
    @Mock InterviewTypeRepository      typeRepo;
    @Mock CandidateRepository          candidateRepo;
    @Mock TenantService                tenantService;
    @Mock JdbcTemplate                 jdbcTemplate;

    @InjectMocks CandidateInterviewService service;

    private static final Long PAYS_ID     = 1L;
    private static final Long ACTOR_ID    = 42L;
    private static final Long CANDIDATE_ID = 10L;
    private static final Long TYPE_ID     = 5L;

    private Candidate candidate(Long paysId) {
        return Candidate.builder()
                .id(CANDIDATE_ID).paysId(paysId).firstName("Alice").lastName("M")
                .emailPersonal("alice@example.com").status(CandidateStatus.ACCEPTED)
                .createdBy(ACTOR_ID).createdAt(OffsetDateTime.now()).build();
    }

    private InterviewType activeType(Long paysId) {
        return InterviewType.builder()
                .id(TYPE_ID).paysId(paysId).name("Entretien RH")
                .orderIndex(1).isActive(true).createdAt(OffsetDateTime.now()).build();
    }

    private CandidateInterview plannedInterview() {
        return CandidateInterview.builder()
                .id(100L).candidateId(CANDIDATE_ID).interviewTypeId(TYPE_ID)
                .scheduledAt(OffsetDateTime.now().plusDays(3))
                .status(InterviewStatus.PLANNED).sequenceNumber(1)
                .createdBy(ACTOR_ID).createdAt(OffsetDateTime.now()).build();
    }

    // ── listByCandidate ───────────────────────────────────────────────────────

    @Test
    void listByCandidate_returnsInterviewsWithTypeName() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findByCandidateIdOrderBySequenceNumber(CANDIDATE_ID))
                .thenReturn(List.of(plannedInterview()));
        when(typeRepo.findAllById(any())).thenReturn(List.of(activeType(PAYS_ID)));
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any())).thenReturn(List.of());

        List<CandidateInterviewDto> result = service.listByCandidate(CANDIDATE_ID);

        assertEquals(1, result.size());
        assertEquals("Entretien RH", result.get(0).interviewTypeName());
        assertEquals("PLANNED", result.get(0).status());
    }

    @Test
    void listByCandidate_candidateFromOtherEntity_throwsException() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(99L)));

        assertThrows(BusinessRuleException.class,
                () -> service.listByCandidate(CANDIDATE_ID));
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_validRequest_setsCorrectSequenceNumber() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(2L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any())).thenReturn(List.of());

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), "Salle A", null, null);
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertEquals(3, result.sequenceNumber());
        assertEquals("PLANNED", result.status());
        assertNull(result.result());
    }

    @Test
    void create_duplicatePlanned_throwsBusinessRuleException() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(true);

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(1), null, null, null);
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.create(CANDIDATE_ID, req, ACTOR_ID));

        assertTrue(ex.getMessage().contains("déjà planifié"));
    }

    @Test
    void create_inactiveType_throwsBusinessRuleException() {
        InterviewType inactiveType = InterviewType.builder()
                .id(TYPE_ID).paysId(PAYS_ID).name("Entretien RH")
                .isActive(false).createdAt(OffsetDateTime.now()).build();
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(inactiveType));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(1), null, null, null);
        assertThrows(BusinessRuleException.class,
                () -> service.create(CANDIDATE_ID, req, ACTOR_ID));
    }

    @Test
    void create_typeFromDifferentEntity_throwsBusinessRuleException() {
        InterviewType wrongEntityType = InterviewType.builder()
                .id(TYPE_ID).paysId(99L).name("Entretien Autre Entité")
                .isActive(true).createdAt(OffsetDateTime.now()).build();
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(wrongEntityType));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(1), null, null, null);
        assertThrows(BusinessRuleException.class,
                () -> service.create(CANDIDATE_ID, req, ACTOR_ID));
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_setResultWhenStatusDone_succeeds() {
        CandidateInterview interview = CandidateInterview.builder()
                .id(100L).candidateId(CANDIDATE_ID).interviewTypeId(TYPE_ID)
                .scheduledAt(OffsetDateTime.now().minusDays(1))
                .status(InterviewStatus.PLANNED).sequenceNumber(1)
                .createdBy(ACTOR_ID).createdAt(OffsetDateTime.now()).build();
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any())).thenReturn(List.of());

        // Setting status=DONE and result=PASS in same request
        UpdateInterviewRequest req = new UpdateInterviewRequest(null, null, null, null, "DONE", "PASS");
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        assertEquals("DONE", result.status());
        assertEquals("PASS", result.result());
    }

    @Test
    void update_setResultWhenNotDone_throwsBusinessRuleException() {
        CandidateInterview interview = CandidateInterview.builder()
                .id(100L).candidateId(CANDIDATE_ID).interviewTypeId(TYPE_ID)
                .scheduledAt(OffsetDateTime.now().plusDays(1))
                .status(InterviewStatus.PLANNED).sequenceNumber(1)
                .createdBy(ACTOR_ID).createdAt(OffsetDateTime.now()).build();
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));

        // result given but status stays PLANNED
        UpdateInterviewRequest req = new UpdateInterviewRequest(null, null, null, null, null, "PASS");
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(100L, req, ACTOR_ID));

        assertTrue(ex.getMessage().contains("DONE"));
    }

    @Test
    void update_candidateFromAnotherEntity_throwsException() {
        CandidateInterview interview = CandidateInterview.builder()
                .id(100L).candidateId(CANDIDATE_ID).interviewTypeId(TYPE_ID)
                .status(InterviewStatus.PLANNED).sequenceNumber(1)
                .scheduledAt(OffsetDateTime.now()).createdBy(ACTOR_ID)
                .createdAt(OffsetDateTime.now()).build();
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(99L)));

        UpdateInterviewRequest req = new UpdateInterviewRequest(null, "New Location", null, null, null, null);
        assertThrows(BusinessRuleException.class, () -> service.update(100L, req, ACTOR_ID));
    }

    @Test
    void update_interviewNotFound_throwsResourceNotFoundException() {
        when(interviewRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(999L,
                        new UpdateInterviewRequest(null, null, null, null, null, null), ACTOR_ID));
    }
}
