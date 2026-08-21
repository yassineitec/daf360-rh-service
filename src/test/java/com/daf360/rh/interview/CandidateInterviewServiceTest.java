package com.daf360.rh.interview;

import com.daf360.rh.domain.Candidate;
import com.daf360.rh.domain.CandidateInterview;
import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.CandidateStatus;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CandidateInterviewDto;
import com.daf360.rh.dto.interview.CreateInterviewRequest;
import com.daf360.rh.dto.interview.UpdateInterviewRequest;
import com.daf360.rh.dto.interview.UserPickerDto;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewInterviewerRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateInterviewServiceTest {

    @Mock CandidateInterviewRepository interviewRepo;
    @Mock CandidateInterviewInterviewerRepository panelRepo;
    @Mock InterviewTypeRepository      typeRepo;
    @Mock CandidateRepository          candidateRepo;
    @Mock TenantService                tenantService;
    @Mock JdbcTemplate                 jdbcTemplate;
    @Mock com.daf360.rh.service.calendar.GraphCalendarService graphCalendarService;

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

    /**
     * Stubs the userEmails() lookup (the {@code COALESCE(email, username)} query)
     * to resolve the given userId → email pairs, faking one JDBC row per entry via
     * a mocked {@link java.sql.ResultSet}. Pass a {@link LinkedHashMap} when a
     * test's assertions care about which entry is "first".
     */
    private void stubUserEmails(Map<Long, String> emailsByUserId) {
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    RowMapper<Object> mapper = inv.getArgument(1);
                    List<Object> rows = new ArrayList<>();
                    int i = 0;
                    for (Map.Entry<Long, String> e : emailsByUserId.entrySet()) {
                        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                        when(rs.getLong("id")).thenReturn(e.getKey());
                        when(rs.getString("email")).thenReturn(e.getValue());
                        rows.add(mapper.mapRow(rs, i++));
                    }
                    return rows;
                });
    }

    // ── listByCandidate ───────────────────────────────────────────────────────

    @Test
    void listByCandidate_returnsInterviewsWithTypeName() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findByCandidateIdOrderBySequenceNumber(CANDIDATE_ID))
                .thenReturn(List.of(plannedInterview()));
        when(typeRepo.findAllById(any())).thenReturn(List.of(activeType(PAYS_ID)));

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

        // Setting status=DONE and result=PASS in same request
        UpdateInterviewRequest req = new UpdateInterviewRequest(null, null, null, null, null, "DONE", "PASS");
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
        UpdateInterviewRequest req = new UpdateInterviewRequest(null, null, null, null, null, null, "PASS");
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

        UpdateInterviewRequest req = new UpdateInterviewRequest(null, null, "New Location", null, null, null, null);
        assertThrows(BusinessRuleException.class, () -> service.update(100L, req, ACTOR_ID));
    }

    @Test
    void update_doneInterview_isRejected() {
        CandidateInterview done = CandidateInterview.builder()
                .id(100L).candidateId(CANDIDATE_ID).interviewTypeId(TYPE_ID)
                .scheduledAt(OffsetDateTime.now().minusDays(2))
                .status(InterviewStatus.DONE).sequenceNumber(1)
                .createdBy(ACTOR_ID).createdAt(OffsetDateTime.now()).build();
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(done));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(1), null, null, null, null, null);
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.update(100L, req, ACTOR_ID));

        assertTrue(ex.getMessage().contains("terminé"));
        verify(interviewRepo, never()).save(any());
    }

    @Test
    void update_replacesPanelAndKeepsFirstAsLead() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(7L);
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, List.of(9L, 3L, 9L), null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        // De-duplicated, order preserved, first entry promoted to lead
        assertEquals(List.of(9L, 3L), result.interviewers().stream().map(UserPickerDto::id).toList());
        assertEquals(Long.valueOf(9L), result.interviewerUserId());
        verify(panelRepo).deleteByInterviewId(100L);
        verify(panelRepo).saveAll(any());
    }

    @Test
    void create_multipleInterviewers_checksEachForConflicts() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, List.of(4L, 5L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertEquals(2, result.interviewers().size());
        assertEquals(Long.valueOf(4L), result.interviewerUserId());
        verify(interviewRepo).findInterviewerConflicts(eq(4L), any(), any());
        verify(interviewRepo).findInterviewerConflicts(eq(5L), any(), any());
        verify(panelRepo).saveAll(any());
    }

    @Test
    void create_interviewerAlreadyBooked_throwsBusinessRuleException() {
        OffsetDateTime slot = OffsetDateTime.now().plusDays(3);
        CandidateInterview clash = plannedInterview();
        clash.setScheduledAt(slot);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(eq(4L), any(), any())).thenReturn(List.of(clash));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, slot, null, null, List.of(4L));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.create(CANDIDATE_ID, req, ACTOR_ID));

        assertTrue(ex.getMessage().contains("déjà un entretien planifié"));
    }

    @Test
    void update_interviewNotFound_throwsResourceNotFoundException() {
        when(interviewRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(999L,
                        new UpdateInterviewRequest(null, null, null, null, null, null, null), ACTOR_ID));
    }

    // ── calendar sync ─────────────────────────────────────────────────────────

    @Test
    void create_withPanel_createsCalendarEventUnderLeadOrganizer() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        // Panel order is [5L, 4L] — 5L is FIRST, so 5L (not 4L) must become the lead
        // organizer. This is what distinguishes this test from
        // create_withResolvedLeadEmail_callsCreateEventWithAllAttendees (which fixes
        // the lead as panel-position-0 too, but focuses on the attendee list contents
        // rather than on "lead = first panel entry" specifically): here both ids
        // resolve to real emails, so the only thing that decides who the organizer is
        // is panel order, not which id "looks like" a lead.
        Map<Long, String> emails = new LinkedHashMap<>();
        emails.put(5L, "organizer-candidate@arx.ing");
        emails.put(4L, "not-the-organizer@arx.ing");
        stubUserEmails(emails);

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), "Salle A", null, List.of(5L, 4L));
        service.create(CANDIDATE_ID, req, ACTOR_ID);

        // The event must be created under the FIRST panel entry's email (the lead),
        // never under the second panel member's email.
        verify(graphCalendarService).createEvent(
                eq("organizer-candidate@arx.ing"), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).createEvent(
                eq("not-the-organizer@arx.ing"), any(), any(), any(), any(), any());
    }

    @Test
    void create_withResolvedLeadEmail_callsCreateEventWithAllAttendees() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        // Simulate two rows: lead (4L) and panel member (5L)
        Map<Long, String> emails = new LinkedHashMap<>();
        emails.put(4L, "lead@arx.ing");
        emails.put(5L, "other@arx.ing");
        stubUserEmails(emails);
        when(graphCalendarService.createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-1", "https://teams.microsoft.com/l/meetup-join/evt-1")));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), "Salle A", null, List.of(4L, 5L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-1", result.graphJoinUrl());
        verify(graphCalendarService).createEvent(
                eq("lead@arx.ing"), any(),
                eq(req.scheduledAt()), eq(req.scheduledAt().plusMinutes(60)),
                argThat(attendees -> ((List<?>) attendees).containsAll(
                        List.of("other@arx.ing", "alice@example.com"))),
                eq("Salle A"));
    }

    @Test
    void create_emptyPanel_neverCallsCalendarService() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, null);
        service.create(CANDIDATE_ID, req, ACTOR_ID);

        verifyNoInteractions(graphCalendarService);
    }

    @Test
    void update_toCancelled_callsCancelEventAndClearsFields() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        interview.setGraphJoinUrl("https://teams.microsoft.com/l/meetup-join/evt-1");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, null, "CANCELLED", null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).cancelEvent(eq("lead@arx.ing"), eq("evt-1"), any());
        assertNull(result.graphJoinUrl());
        assertNull(interview.getGraphEventId());
    }

    @Test
    void update_toDone_neverTouchesCalendarService() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, null, "DONE", "PASS");
        service.update(100L, req, ACTOR_ID);

        verifyNoInteractions(graphCalendarService);
    }

    @Test
    void update_leadInterviewerChanges_cancelsOldEventAndCreatesNewOne() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-old");
        interview.setGraphOrganizerEmail("old-lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        stubUserEmails(Map.of(9L, "new-lead@arx.ing"));
        when(graphCalendarService.createEvent(eq("new-lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-new", "https://teams.microsoft.com/l/meetup-join/evt-new")));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, List.of(9L), null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).cancelEvent(eq("old-lead@arx.ing"), eq("evt-old"), any());
        verify(graphCalendarService).createEvent(eq("new-lead@arx.ing"), any(), any(), any(), any(), any());
        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-new", result.graphJoinUrl());
    }

    @Test
    void update_sameOrganizerExistingEvent_patchesInPlaceWithoutCreatingAnother() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // req.interviewerUserIds() is null (panel unchanged) — update() falls back to
        // the STORED panel via panelOf(), so it must reflect the existing lead (4L).
        when(panelRepo.findByInterviewIdOrderByIdAsc(100L)).thenReturn(List.of(
                com.daf360.rh.domain.CandidateInterviewInterviewer.builder()
                        .id(1L).interviewId(100L).userId(4L).build()));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        stubUserEmails(Map.of(4L, "lead@arx.ing"));
        when(graphCalendarService.updateEvent(eq("lead@arx.ing"), eq("evt-1"), any(), any(), any(), any(), any()))
                .thenReturn(com.daf360.rh.service.calendar.GraphCalendarService.UpdateOutcome.SUCCESS);

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(5), "Salle B", null, null, null, null);
        service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService).updateEvent(eq("lead@arx.ing"), eq("evt-1"), any(), any(), any(), any(), eq("Salle B"));
        verify(graphCalendarService, never()).createEvent(any(), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).cancelEvent(any(), any(), any());
    }

    @Test
    void update_patchFails_fallsBackToCreatingFreshEvent() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-stale");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // req.interviewerUserIds() is null (panel unchanged) — update() falls back to
        // the STORED panel via panelOf(), so it must reflect the existing lead (4L).
        when(panelRepo.findByInterviewIdOrderByIdAsc(100L)).thenReturn(List.of(
                com.daf360.rh.domain.CandidateInterviewInterviewer.builder()
                        .id(1L).interviewId(100L).userId(4L).build()));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        stubUserEmails(Map.of(4L, "lead@arx.ing"));
        // The stored event was genuinely deleted out from under us (e.g. manually in
        // Outlook) — updateEvent reports EVENT_NOT_FOUND, so the self-heal path must
        // kick in.
        when(graphCalendarService.updateEvent(eq("lead@arx.ing"), eq("evt-stale"), any(), any(), any(), any(), any()))
                .thenReturn(com.daf360.rh.service.calendar.GraphCalendarService.UpdateOutcome.EVENT_NOT_FOUND);
        when(graphCalendarService.createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new com.daf360.rh.service.calendar.GraphCalendarService.CreatedEvent(
                        "evt-fresh", "https://teams.microsoft.com/l/meetup-join/evt-fresh")));

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(5), null, null, null, null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        // Self-healed: a fresh event was created under the SAME organizer (no
        // organizer change here, just a dead event id) — and NOT preceded by a
        // cancelEvent call, since there's nothing valid left to cancel.
        verify(graphCalendarService).createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).cancelEvent(any(), any(), any());
        assertEquals("evt-fresh", interview.getGraphEventId());
        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-fresh", result.graphJoinUrl());
    }

    @Test
    void update_patchFailsTransiently_leavesExistingEventUntouched() {
        // Regression test for the duplicate-event bug: a transient/ambiguous PATCH
        // failure (throttling, timeout, network blip — reported as FAILED, NOT
        // EVENT_NOT_FOUND) must never trigger a recreate. Recreating here would risk
        // a duplicate invite if the original PATCH actually landed despite a
        // timed-out response, or orphan a still-live original event if it didn't.
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        interview.setGraphJoinUrl("https://teams.microsoft.com/l/meetup-join/evt-1");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(panelRepo.findByInterviewIdOrderByIdAsc(100L)).thenReturn(List.of(
                com.daf360.rh.domain.CandidateInterviewInterviewer.builder()
                        .id(1L).interviewId(100L).userId(4L).build()));
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        stubUserEmails(Map.of(4L, "lead@arx.ing"));
        when(graphCalendarService.updateEvent(eq("lead@arx.ing"), eq("evt-1"), any(), any(), any(), any(), any()))
                .thenReturn(com.daf360.rh.service.calendar.GraphCalendarService.UpdateOutcome.FAILED);

        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, OffsetDateTime.now().plusDays(5), null, null, null, null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        verify(graphCalendarService, never()).createEvent(any(), any(), any(), any(), any(), any());
        verify(graphCalendarService, never()).cancelEvent(any(), any(), any());
        assertEquals("evt-1", interview.getGraphEventId(), "the original event id must not be overwritten or nulled");
        assertEquals("https://teams.microsoft.com/l/meetup-join/evt-1", result.graphJoinUrl());
    }

    @Test
    void syncCalendarEvent_neverThrows_whenEmailLookupBlowsUp() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("COALESCE(email, username)"), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("DB connection reset"));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, List.of(4L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertNotNull(result); // create() itself must succeed regardless
        verify(interviewRepo).save(any());
        // Email resolution blew up before the calendar service was ever reached.
        verifyNoInteractions(graphCalendarService);
    }

    @Test
    void syncCalendarEvent_neverThrows_whenCalendarServiceBlowsUp() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        // Email resolution succeeds normally this time — the failure is in
        // GraphCalendarService itself, thrown directly from createEvent().
        stubUserEmails(Map.of(4L, "lead@arx.ing"));
        when(graphCalendarService.createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Graph API unreachable"));

        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, List.of(4L));
        CandidateInterviewDto result = service.create(CANDIDATE_ID, req, ACTOR_ID);

        assertNotNull(result); // create() itself must succeed regardless
        verify(interviewRepo).save(any());
        verify(graphCalendarService).createEvent(eq("lead@arx.ing"), any(), any(), any(), any(), any());
    }

    @Test
    void update_panelClearedWhilePlanned_cancelsOrphanedEvent() {
        CandidateInterview interview = plannedInterview();
        interview.setInterviewerUserId(4L);
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizerEmail("lead@arx.ing");
        interview.setGraphJoinUrl("https://teams.microsoft.com/l/meetup-join/evt-1");
        when(interviewRepo.findById(100L)).thenReturn(Optional.of(interview));
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(interviewRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // Panel cleared entirely (interviewerUserIds: []) — status is left unchanged
        // (omitted), which for an interview created via plannedInterview() stays PLANNED.
        UpdateInterviewRequest req = new UpdateInterviewRequest(
                null, null, null, null, List.of(), null, null);
        CandidateInterviewDto result = service.update(100L, req, ACTOR_ID);

        // With no panel left to organize under, this must be treated like a
        // cancellation — the Graph event must not be left orphaned.
        verify(graphCalendarService).cancelEvent(eq("lead@arx.ing"), eq("evt-1"), any());
        assertNull(interview.getGraphEventId());
        assertNull(result.graphJoinUrl());
    }

    @Test
    void create_singleInterviewerPanel_attendeesAreCandidateOnly() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(candidateRepo.findById(CANDIDATE_ID)).thenReturn(Optional.of(candidate(PAYS_ID)));
        when(typeRepo.findById(TYPE_ID)).thenReturn(Optional.of(activeType(PAYS_ID)));
        when(interviewRepo.existsByCandidateIdAndInterviewTypeIdAndStatus(
                CANDIDATE_ID, TYPE_ID, InterviewStatus.PLANNED)).thenReturn(false);
        when(interviewRepo.findInterviewerConflicts(any(), any(), any())).thenReturn(List.of());
        when(interviewRepo.countByCandidateId(CANDIDATE_ID)).thenReturn(0L);
        when(interviewRepo.save(any())).thenAnswer(i -> {
            CandidateInterview ci = i.getArgument(0);
            ci.setId(100L);
            return ci;
        });
        when(jdbcTemplate.query(contains("fullName"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        stubUserEmails(Map.of(4L, "lead@arx.ing"));

        // Single-entry panel: 4L is the (only) lead — there's no second panel member
        // to add as an attendee.
        CreateInterviewRequest req = new CreateInterviewRequest(
                TYPE_ID, OffsetDateTime.now().plusDays(3), null, null, List.of(4L));
        service.create(CANDIDATE_ID, req, ACTOR_ID);

        // The attendee list must be exactly the candidate's own email — no panel
        // member entries, since there are none besides the organizer.
        verify(graphCalendarService).createEvent(
                eq("lead@arx.ing"), any(), any(), any(),
                eq(List.of("alice@example.com")), any());
    }
}
