package com.daf360.rh.interview;

import com.daf360.rh.domain.InterviewType;
import com.daf360.rh.domain.enums.InterviewStatus;
import com.daf360.rh.dto.interview.CreateInterviewTypeRequest;
import com.daf360.rh.dto.interview.InterviewTypeDto;
import com.daf360.rh.dto.interview.UpdateInterviewTypeRequest;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.exception.ResourceNotFoundException;
import com.daf360.rh.repository.CandidateInterviewRepository;
import com.daf360.rh.repository.InterviewTypeRepository;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.InterviewTypeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterviewTypeServiceTest {

    @Mock InterviewTypeRepository      typeRepo;
    @Mock CandidateInterviewRepository interviewRepo;
    @Mock TenantService                tenantService;

    @InjectMocks InterviewTypeService service;

    private static final Long PAYS_ID  = 1L;
    private static final Long ACTOR_ID = 42L;

    private InterviewType type(Long id, Long paysId, boolean active) {
        return InterviewType.builder()
                .id(id).paysId(paysId).name("Entretien RH")
                .description("desc").orderIndex(1).isActive(active)
                .createdAt(OffsetDateTime.now()).build();
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_withPaysId_returnsFilteredTypes() {
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findByPaysIdOrderByOrderIndexAsc(PAYS_ID))
                .thenReturn(List.of(type(1L, PAYS_ID, true)));

        List<InterviewTypeDto> result = service.list();

        assertEquals(1, result.size());
        assertEquals(PAYS_ID, result.get(0).paysId());
        verify(typeRepo).findByPaysIdOrderByOrderIndexAsc(PAYS_ID);
    }

    @Test
    void list_adminNull_returnsAll() {
        when(tenantService.getEffectivePaysId()).thenReturn(null);
        when(typeRepo.findAll()).thenReturn(List.of(type(1L, PAYS_ID, true), type(2L, 2L, true)));

        List<InterviewTypeDto> result = service.list();

        assertEquals(2, result.size());
        verify(typeRepo).findAll();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_validRequest_persistsAndReturnsDto() {
        CreateInterviewTypeRequest req = new CreateInterviewTypeRequest(PAYS_ID, "Entretien RH", "desc", 1);
        InterviewType saved = type(10L, PAYS_ID, true);
        when(typeRepo.save(any())).thenReturn(saved);

        InterviewTypeDto result = service.create(req, ACTOR_ID);

        assertEquals(10L, result.id());
        assertTrue(result.isActive());
        verify(typeRepo).save(any(InterviewType.class));
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_sameEntity_updatesFields() {
        InterviewType existing = type(5L, PAYS_ID, true);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(typeRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdateInterviewTypeRequest req = new UpdateInterviewTypeRequest("Updated Name", "new desc", 2);
        InterviewTypeDto result = service.update(5L, req, ACTOR_ID);

        assertEquals("Updated Name", result.name());
        assertEquals(2, result.orderIndex());
    }

    @Test
    void update_differentEntity_throwsException() {
        InterviewType existing = type(5L, 99L, true);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findById(5L)).thenReturn(Optional.of(existing));

        assertThrows(BusinessRuleException.class,
                () -> service.update(5L, new UpdateInterviewTypeRequest("X", null, null), ACTOR_ID));
    }

    @Test
    void update_notFound_throwsResourceNotFoundException() {
        when(typeRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(99L, new UpdateInterviewTypeRequest("X", null, null), ACTOR_ID));
    }

    // ── deactivate ────────────────────────────────────────────────────────────

    @Test
    void deactivate_noPlannedInterviews_setsInactive() {
        InterviewType existing = type(5L, PAYS_ID, true);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(interviewRepo.countByInterviewTypeIdAndStatus(5L, InterviewStatus.PLANNED)).thenReturn(0L);
        when(typeRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewTypeDto result = service.deactivate(5L, ACTOR_ID);

        assertFalse(result.isActive());
    }

    @Test
    void deactivate_withPlannedInterviews_throwsException() {
        InterviewType existing = type(5L, PAYS_ID, true);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(interviewRepo.countByInterviewTypeIdAndStatus(5L, InterviewStatus.PLANNED)).thenReturn(2L);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.deactivate(5L, ACTOR_ID));

        assertTrue(ex.getMessage().contains("2 entretien(s) planifié(s)"));
    }

    // ── activate ─────────────────────────────────────────────────────────────

    @Test
    void activate_inactiveType_setsActive() {
        InterviewType existing = type(5L, PAYS_ID, false);
        when(tenantService.getEffectivePaysId()).thenReturn(PAYS_ID);
        when(typeRepo.findById(5L)).thenReturn(Optional.of(existing));
        when(typeRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewTypeDto result = service.activate(5L, ACTOR_ID);

        assertTrue(result.isActive());
    }
}
