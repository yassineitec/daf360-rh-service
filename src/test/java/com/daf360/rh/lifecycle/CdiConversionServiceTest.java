package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.CdiConversionRequest;
import com.daf360.rh.domain.CdiConversionStep;
import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.enums.CdiConversionStatus;
import com.daf360.rh.dto.lifecycle.CdiConversionDecisionRequest;
import com.daf360.rh.dto.lifecycle.CdiConversionDto;
import com.daf360.rh.dto.lifecycle.SubmitCdiConversionRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.lists.ContractTypeRefs;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.CdiConversionRequestRepository;
import com.daf360.rh.repository.CdiConversionStepRepository;
import com.daf360.rh.repository.EmployeeContractRepository;
import com.daf360.rh.security.PaysScopeContext;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** CIVP → CDI conversion requests: who may act when, four-eyes, escalation, notifications. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CdiConversionServiceTest {

    @Mock CdiConversionRequestRepository requestRepo;
    @Mock CdiConversionStepRepository    stepRepo;
    @Mock EmployeeContractRepository     contractRepo;
    @Mock ContractTypeRefs               contractTypeRefs;
    @Mock NotificationRoutingService     notifications;
    @Mock TenantService                  tenantService;
    @Mock AuditService                   auditService;
    @Mock JdbcTemplate                   jdbc;

    CdiConversionService service;

    static final long PAYS = 179L, CONTRACT = 5L, EMPLOYEE_USER = 900L;
    static final long REQUESTER = 10L, VALIDATOR = 20L, ARBITER = 30L, HR = 40L;
    EmployeeContract civp;

    @BeforeEach
    void setup() {
        service = new CdiConversionService(requestRepo, stepRepo, contractRepo, contractTypeRefs,
                notifications, tenantService, auditService, jdbc);
        ReflectionTestUtils.setField(service, "fourEyes", true);

        EmployeeProfile profile = EmployeeProfile.builder().id(77L).userId(EMPLOYEE_USER).build();
        civp = EmployeeContract.builder().id(CONTRACT).employeeProfile(profile).paysId(PAYS)
                .contractTypeId("301").currentStatusCode("ACTIF").isActive(true)
                .dateDebut(LocalDate.of(2025, 11, 1)).build();
        when(contractRepo.findById(CONTRACT)).thenReturn(Optional.of(civp));
        when(contractTypeRefs.natureOf("301", PAYS)).thenReturn("CIVP");
        when(tenantService.getPaysScope()).thenReturn(new PaysScopeContext.Scope(false, Set.of(PAYS)));
        when(requestRepo.save(any())).thenAnswer(inv -> {
            CdiConversionRequest r = inv.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            return r;
        });
        when(jdbc.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("Nom"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(long userId, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(String.valueOf(userId), null, permissions));
    }

    private static SubmitCdiConversionRequest submitDto() {
        SubmitCdiConversionRequest d = new SubmitCdiConversionRequest();
        d.setRequestedStartDate(LocalDate.of(2026, 11, 1));
        d.setProposedNetSalary(new BigDecimal("2500"));
        d.setJustification("Très bons retours des 3 responsables");
        return d;
    }

    private static CdiConversionDecisionRequest decision(CdiConversionDecisionRequest.Action a, String comment) {
        CdiConversionDecisionRequest d = new CdiConversionDecisionRequest();
        d.setAction(a);
        d.setComment(comment);
        return d;
    }

    private CdiConversionRequest pending(CdiConversionStatus status, Long escalatedBy) {
        CdiConversionRequest r = CdiConversionRequest.builder().id(1L).contractId(CONTRACT).employeeProfileId(77L)
                .paysId(PAYS).status(status).requestedBy(REQUESTER).escalatedBy(escalatedBy)
                .requestedStartDate(LocalDate.of(2026, 11, 1)).proposedNetSalary(new BigDecimal("2500.000"))
                .justification("x").build();
        when(requestRepo.findById(1L)).thenReturn(Optional.of(r));
        return r;
    }

    private String lastEvent() {
        ArgumentCaptor<RoutingContext> ctx = ArgumentCaptor.forClass(RoutingContext.class);
        verify(notifications, atLeastOnce()).dispatchAfterCommit(ctx.capture());
        return ctx.getValue().getEventCode();
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    @Test
    void submit_onAnActiveCivp_createsASubmittedRequest_andNotifiesTheValidators() {
        loginAs(REQUESTER, CdiConversionService.REQUEST);

        CdiConversionDto dto = service.submit(CONTRACT, submitDto(), REQUESTER);

        assertEquals("SUBMITTED", dto.status());
        assertEquals(0, new BigDecimal("2500.000").compareTo(dto.proposedNetSalary()));
        assertFalse(dto.canDecide());                       // the requester never decides
        assertTrue(dto.canCancel());
        ArgumentCaptor<CdiConversionStep> step = ArgumentCaptor.forClass(CdiConversionStep.class);
        verify(stepRepo).save(step.capture());
        assertEquals("SUBMIT", step.getValue().getAction());
        assertEquals("CDI_CONVERSION_SUBMITTED", lastEvent());
    }

    @Test
    void submit_isRefused_forACdd_anInactiveCivp_aPendingRequest_orWithoutThePermission() {
        loginAs(REQUESTER);
        assertThrows(AppException.class, () -> service.submit(CONTRACT, submitDto(), REQUESTER));

        loginAs(REQUESTER, CdiConversionService.REQUEST);
        when(contractTypeRefs.natureOf("301", PAYS)).thenReturn("CDD");
        assertThrows(BusinessRuleException.class, () -> service.submit(CONTRACT, submitDto(), REQUESTER));

        when(contractTypeRefs.natureOf("301", PAYS)).thenReturn("CIVP");
        civp.setCurrentStatusCode("PERIODE_ESSAI");
        assertThrows(BusinessRuleException.class, () -> service.submit(CONTRACT, submitDto(), REQUESTER));

        civp.setCurrentStatusCode("ACTIF");
        when(requestRepo.findByContractIdAndStatusIn(eq(CONTRACT), any()))
                .thenReturn(List.of(CdiConversionRequest.builder().status(CdiConversionStatus.ESCALATED).build()));
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> service.submit(CONTRACT, submitDto(), REQUESTER));
        assertTrue(e.getMessage().contains("déjà en cours"));
        verify(requestRepo, never()).save(any());
    }

    @Test
    void submit_forOneself_isForbidden() {
        loginAs(EMPLOYEE_USER, CdiConversionService.REQUEST);
        assertThrows(AppException.class, () -> service.submit(CONTRACT, submitDto(), EMPLOYEE_USER));
    }

    @Test
    void submit_outsideThePaysScope_isForbidden() {
        loginAs(REQUESTER, CdiConversionService.REQUEST);
        when(tenantService.getPaysScope()).thenReturn(new PaysScopeContext.Scope(false, Set.of(1L)));
        assertThrows(AppException.class, () -> service.submit(CONTRACT, submitDto(), REQUESTER));
    }

    // ── Validator ─────────────────────────────────────────────────────────────

    @Test
    void validator_approves_withAnAdjustedSalary_andRhIsNotified() {
        pending(CdiConversionStatus.SUBMITTED, null);
        loginAs(VALIDATOR, CdiConversionService.VALIDATE);
        CdiConversionDecisionRequest d = decision(CdiConversionDecisionRequest.Action.APPROVE, null);
        d.setNetSalary(new BigDecimal("2400"));

        CdiConversionDto dto = service.decide(1L, d, VALIDATOR);

        assertEquals("APPROVED", dto.status());
        assertEquals(0, new BigDecimal("2400.000").compareTo(dto.validatedNetSalary()));
        assertEquals(LocalDate.of(2026, 11, 1), dto.validatedStartDate());   // kept from the request
        assertEquals(VALIDATOR, dto.decidedBy());
        assertEquals("CDI_CONVERSION_APPROVED", lastEvent());
    }

    @Test
    void requester_cannotDecideTheirOwnRequest_unlessFourEyesIsSwitchedOff() {
        pending(CdiConversionStatus.SUBMITTED, null);
        loginAs(REQUESTER, CdiConversionService.REQUEST, CdiConversionService.VALIDATE);

        assertThrows(AppException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.APPROVE, null), REQUESTER));

        ReflectionTestUtils.setField(service, "fourEyes", false);
        assertEquals("APPROVED",
                service.decide(1L, decision(CdiConversionDecisionRequest.Action.APPROVE, null), REQUESTER).status());
    }

    @Test
    void rejectOrEscalate_withoutAComment_isRefused() {
        pending(CdiConversionStatus.SUBMITTED, null);
        loginAs(VALIDATOR, CdiConversionService.VALIDATE);

        assertThrows(BusinessRuleException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.REJECT, " "), VALIDATOR));
        assertThrows(BusinessRuleException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.ESCALATE, null), VALIDATOR));
    }

    // ── Escalation ────────────────────────────────────────────────────────────

    @Test
    void escalation_goesToTheArbiter_whoDecides_notTheValidator() {
        CdiConversionRequest r = pending(CdiConversionStatus.SUBMITTED, null);
        loginAs(VALIDATOR, CdiConversionService.VALIDATE);

        CdiConversionDto escalated = service.decide(1L,
                decision(CdiConversionDecisionRequest.Action.ESCALATE, "Salaire au-dessus de la grille"), VALIDATOR);
        assertEquals("ESCALATED", escalated.status());
        assertEquals(VALIDATOR, r.getEscalatedBy());
        assertEquals("CDI_CONVERSION_ESCALATED", lastEvent());
        assertFalse(escalated.canDecide());                 // the validator has no say anymore

        // A plain validator cannot act on an escalated request.
        assertThrows(AppException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.APPROVE, null), VALIDATOR));

        loginAs(ARBITER, CdiConversionService.ARBITRATE);
        assertThrows(BusinessRuleException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.ESCALATE, "encore"), ARBITER));
        CdiConversionDto decided = service.decide(1L,
                decision(CdiConversionDecisionRequest.Action.REJECT, "Budget 2026 gelé"), ARBITER);
        assertEquals("REJECTED", decided.status());
        assertEquals(ARBITER, decided.decidedBy());
        assertEquals("CDI_CONVERSION_REJECTED", lastEvent());
    }

    @Test
    void theValidatorWhoEscalated_cannotArbitrate_evenWithTheArbiterPermission() {
        pending(CdiConversionStatus.ESCALATED, VALIDATOR);
        loginAs(VALIDATOR, CdiConversionService.VALIDATE, CdiConversionService.ARBITRATE);

        assertThrows(AppException.class,
                () -> service.decide(1L, decision(CdiConversionDecisionRequest.Action.APPROVE, null), VALIDATOR));
    }

    // ── Cancel / conversion hooks ─────────────────────────────────────────────

    @Test
    void cancel_byAStranger_isForbidden_byTheRequester_works() {
        pending(CdiConversionStatus.SUBMITTED, null);
        loginAs(VALIDATOR, CdiConversionService.VALIDATE);
        assertThrows(AppException.class, () -> service.cancel(1L, null, VALIDATOR));

        loginAs(REQUESTER, CdiConversionService.REQUEST);
        assertEquals("CANCELLED", service.cancel(1L, "Doublon", REQUESTER).status());
        assertEquals("CDI_CONVERSION_CANCELLED", lastEvent());
    }

    @Test
    void conversion_requiresAnApprovedRequest_thenMarksItConverted() {
        when(requestRepo.findByContractIdAndStatusIn(eq(CONTRACT), any())).thenReturn(List.of());
        assertThrows(BusinessRuleException.class, () -> service.requireApproved(CONTRACT));

        CdiConversionRequest approved = CdiConversionRequest.builder().id(1L).contractId(CONTRACT)
                .status(CdiConversionStatus.APPROVED).validatedNetSalary(new BigDecimal("2400")).build();
        when(requestRepo.findByContractIdAndStatusIn(eq(CONTRACT), any())).thenReturn(List.of(approved));
        assertSame(approved, service.requireApproved(CONTRACT));

        service.markConverted(approved, 88L, LocalDate.of(2026, 11, 1), HR);
        assertEquals(88L, approved.getConvertedContractId());
        assertFalse(approved.isAwaitingConversion());
    }

    @Test
    void todoList_showsEachRoleOnlyWhatWaitsForIt() {
        CdiConversionRequest submitted = pending(CdiConversionStatus.SUBMITTED, null);
        CdiConversionRequest escalated = CdiConversionRequest.builder().id(2L).contractId(CONTRACT).employeeProfileId(77L)
                .paysId(PAYS).status(CdiConversionStatus.ESCALATED).requestedBy(REQUESTER).escalatedBy(VALIDATOR)
                .requestedStartDate(LocalDate.of(2026, 11, 1)).proposedNetSalary(BigDecimal.TEN).justification("x").build();
        when(requestRepo.findByPaysIdInOrderByCreatedAtDescIdDesc(any())).thenReturn(List.of(submitted, escalated));

        loginAs(VALIDATOR, CdiConversionService.VALIDATE);
        assertEquals(List.of(1L), service.list(true, VALIDATOR).stream().map(CdiConversionDto::id).toList());

        loginAs(ARBITER, CdiConversionService.ARBITRATE);
        assertEquals(List.of(2L), service.list(true, ARBITER).stream().map(CdiConversionDto::id).toList());

        loginAs(REQUESTER, CdiConversionService.REQUEST);
        assertTrue(service.list(true, REQUESTER).isEmpty());
        assertEquals(2, service.list(false, REQUESTER).size());   // their own requests
    }
}
