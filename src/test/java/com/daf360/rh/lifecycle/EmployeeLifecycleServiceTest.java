package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.ContractTypeConfig;
import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeLifecycleAlert;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.notification.NotificationRoutingService;
import com.daf360.rh.notification.NotificationEntityType;
import com.daf360.rh.notification.RoutingContext;
import com.daf360.rh.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeLifecycleServiceTest {

    @Mock EmployeeContractRepository              contractRepo;
    @Mock EmployeeLifecycleTransitionRepository   transitionRepo;
    @Mock EmployeeLifecycleAlertRepository        alertRepo;
    @Mock ContractTypeConfigRepository            configRepo;
    @Mock EmployeeProfileRepository               profileRepo;
    @Mock LifecycleStateMachine                   stateMachine;
    @Mock NotificationRoutingService              notificationRoutingService;
    @Mock JdbcTemplate                            jdbc;
    @Mock ObjectMapper                            objectMapper;

    @InjectMocks EmployeeLifecycleService service;

    private EmployeeProfile profile;
    private ContractTypeConfig cdiConfig;
    private ContractTypeConfig cddConfig;
    private ContractTypeConfig civpConfig;

    @BeforeEach
    void setup() {
        profile = EmployeeProfile.builder()
            .id(10L)
            .dateOfBirth(LocalDate.of(1990, 1, 1))
            .build();

        cdiConfig = ContractTypeConfig.builder()
            .id(1L).paysId(1L).contractTypeCode("CDI")
            .trialPeriodDaysStandard(30).trialPeriodDaysManager(90)
            .alertDaysBeforeExpiry(30).build();

        cddConfig = ContractTypeConfig.builder()
            .id(2L).paysId(1L).contractTypeCode("CDD")
            .trialPeriodDaysStandard(7)
            .alertDaysBeforeExpiry(30).build();

        civpConfig = ContractTypeConfig.builder()
            .id(3L).paysId(1L).contractTypeCode("CIVP")
            .civpMaxAge(30).civpMaxDurationMonths(12).civpAnetiRequired(true).build();
    }

    // ── 1. Create CDI — happy path ────────────────────────────────────────────

    @Test
    void createCDI_validData_setsRecrutementStatus() throws Exception {
        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDI")).thenReturn(Optional.of(cdiConfig));
        when(contractRepo.save(any())).thenAnswer(inv -> {
            EmployeeContract c = inv.getArgument(0);
            c.setId(100L);
            return c;
        });

        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CDI");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setReferenceContrat("CDI-001");

        ContractDetailDto result = service.createContract(dto, 1L);

        assertThat(result.getCurrentStatusCode()).isEqualTo("RECRUTEMENT");
        ArgumentCaptor<EmployeeContract> captor = ArgumentCaptor.forClass(EmployeeContract.class);
        verify(contractRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getContractTypeCode()).isEqualTo("CDI");
    }

    // ── 2. Create CIVP — age over 30 → exception ─────────────────────────────

    @Test
    void createCIVP_ageOver30_throwsException() {
        EmployeeProfile oldProfile = EmployeeProfile.builder()
            .id(10L)
            .dateOfBirth(LocalDate.now().minusYears(35))
            .build();

        when(profileRepo.findById(10L)).thenReturn(Optional.of(oldProfile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CIVP")).thenReturn(Optional.of(civpConfig));

        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CIVP");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setDateFinPrevue(LocalDate.of(2027, 1, 1));
        dto.setCivpAnetiReference("ANETI-001");

        assertThatThrownBy(() -> service.createContract(dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("moins de 30 ans");
    }

    // ── 3. Create CIVP — missing ANETI ref → exception ───────────────────────

    @Test
    void createCIVP_missingAnetiRef_throwsException() {
        EmployeeProfile youngProfile = EmployeeProfile.builder()
            .id(10L)
            .dateOfBirth(LocalDate.now().minusYears(24))
            .build();

        when(profileRepo.findById(10L)).thenReturn(Optional.of(youngProfile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CIVP")).thenReturn(Optional.of(civpConfig));

        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CIVP");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setDateFinPrevue(LocalDate.of(2027, 1, 1));
        // civpAnetiReference intentionally absent

        assertThatThrownBy(() -> service.createContract(dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("ANETI");
    }

    // ── 4. Create CIVP — duration > 12 months → exception ────────────────────

    @Test
    void createCIVP_durationOver12Months_throwsException() {
        EmployeeProfile youngProfile = EmployeeProfile.builder()
            .id(10L)
            .dateOfBirth(LocalDate.now().minusYears(24))
            .build();

        when(profileRepo.findById(10L)).thenReturn(Optional.of(youngProfile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CIVP")).thenReturn(Optional.of(civpConfig));

        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CIVP");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setDateFinPrevue(LocalDate.of(2028, 1, 1)); // 18 months
        dto.setCivpAnetiReference("ANETI-001");

        assertThatThrownBy(() -> service.createContract(dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("dépasser 12 mois");
    }

    // ── 5. Create CDD with parent — count allows it ───────────────────────────

    @Test
    void createCDD_withParent_incrementsCount() throws Exception {
        EmployeeContract parentCdd = EmployeeContract.builder()
            .id(50L).contractTypeCode("CDD")
            .cddRenouvellementCount(0)
            .employeeProfile(profile).build();

        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(contractRepo.findById(50L)).thenReturn(Optional.of(parentCdd));
        when(contractRepo.save(any())).thenAnswer(inv -> {
            EmployeeContract c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });
        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CDD");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setDateFinPrevue(LocalDate.of(2027, 7, 1));
        dto.setCddContratParentId(50L);

        ContractDetailDto result = service.createContract(dto, 1L);

        assertThat(result).isNotNull();
        ArgumentCaptor<EmployeeContract> captor = ArgumentCaptor.forClass(EmployeeContract.class);
        verify(contractRepo, atLeastOnce()).save(captor.capture());
        EmployeeContract saved = captor.getAllValues().get(0);
        assertThat(saved.getCddContratParent()).isEqualTo(parentCdd);
    }

    // ── 6. Create CDD — parent already renewed → exception ───────────────────

    @Test
    void createCDD_parentAlreadyRenewed_throwsException() {
        EmployeeContract parentCdd = EmployeeContract.builder()
            .id(50L).contractTypeCode("CDD")
            .cddRenouvellementCount(1) // already renewed
            .employeeProfile(profile).build();

        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(contractRepo.findById(50L)).thenReturn(Optional.of(parentCdd));

        CreateContractRequest dto = new CreateContractRequest();
        dto.setEmployeeProfileId(10L);
        dto.setPaysId(1L);
        dto.setContractTypeCode("CDD");
        dto.setDateDebut(LocalDate.of(2026, 7, 1));
        dto.setDateFinPrevue(LocalDate.of(2027, 7, 1));
        dto.setCddContratParentId(50L);

        assertThatThrownBy(() -> service.createContract(dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("renouvelé qu'une seule fois");
    }

    // ── 7. Transition — valid → saves new status ──────────────────────────────

    @Test
    void transitionState_validTransition_succeeds() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(200L).contractTypeCode("CDI")
            .currentStatusCode("RECRUTEMENT")
            .dossierLocked(false)
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(200L)).thenReturn(Optional.of(contract));
        when(stateMachine.isTransitionAllowed("CDI", "RECRUTEMENT", "PERIODE_ESSAI")).thenReturn(true);
        when(contractRepo.save(any())).thenReturn(contract);
        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));

        TransitionRequest dto = TransitionRequest.builder()
            .newStatus("PERIODE_ESSAI").actionCode("START_TRIAL").build();

        ContractDetailDto result = service.transitionState(200L, dto, 1L);

        assertThat(result.getCurrentStatusCode()).isEqualTo("PERIODE_ESSAI");
        verify(contractRepo).save(contract);
    }

    // ── 8. Transition — invalid state machine → exception ────────────────────

    @Test
    void transitionState_invalidTransition_throwsException() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(200L).contractTypeCode("CDI")
            .currentStatusCode("ACTIF")
            .dossierLocked(false)
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(200L)).thenReturn(Optional.of(contract));
        when(stateMachine.isTransitionAllowed("CDI", "ACTIF", "RECRUTEMENT")).thenReturn(false);

        TransitionRequest dto = TransitionRequest.builder()
            .newStatus("RECRUTEMENT").actionCode("ILLEGAL").build();

        assertThatThrownBy(() -> service.transitionState(200L, dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Transition non autorisée");
    }

    // ── 9. Transition — locked dossier → exception ────────────────────────────

    @Test
    void transitionState_lockedDossier_throwsException() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(200L).contractTypeCode("CDI")
            .currentStatusCode("FIN_CONTRAT")
            .dossierLocked(true)
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(200L)).thenReturn(Optional.of(contract));
        when(stateMachine.isTransitionAllowed("CDI", "FIN_CONTRAT", "INACTIF")).thenReturn(true);

        TransitionRequest dto = TransitionRequest.builder()
            .newStatus("INACTIF").actionCode("CLOSE").build();

        assertThatThrownBy(() -> service.transitionState(200L, dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("verrouillé");
    }

    // ── 10. Validate trial — approved → ACTIF ────────────────────────────────

    @Test
    void validateTrialPeriod_approved_setsActif() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(201L).contractTypeCode("CDI")
            .currentStatusCode("PERIODE_ESSAI")
            .dossierLocked(false)
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(201L)).thenReturn(Optional.of(contract));
        when(stateMachine.isTransitionAllowed("CDI", "PERIODE_ESSAI", "ACTIF")).thenReturn(true);
        when(contractRepo.save(any())).thenReturn(contract);
        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));

        ValidateTrialRequest dto = new ValidateTrialRequest();
        dto.setApproved(true);
        dto.setCommentaire("Période validée");

        ContractDetailDto result = service.validateTrialPeriod(201L, dto, 1L);

        assertThat(result.getCurrentStatusCode()).isEqualTo("ACTIF");
    }

    // ── 11. Validate trial — rejected → RUPTURE_PE ───────────────────────────

    @Test
    void validateTrialPeriod_rejected_setsRupturePE() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(202L).contractTypeCode("CDI")
            .currentStatusCode("PERIODE_ESSAI")
            .dossierLocked(false)
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(202L)).thenReturn(Optional.of(contract));
        when(stateMachine.isTransitionAllowed("CDI", "PERIODE_ESSAI", "RUPTURE_PE")).thenReturn(true);
        when(contractRepo.save(any())).thenReturn(contract);
        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));

        ValidateTrialRequest dto = new ValidateTrialRequest();
        dto.setApproved(false);
        dto.setCommentaire("Échec PE");

        ContractDetailDto result = service.validateTrialPeriod(202L, dto, 1L);

        assertThat(result.getCurrentStatusCode()).isEqualTo("RUPTURE_PE");
    }

    // ── 12. Renew CDD — second renewal → exception ───────────────────────────

    @Test
    void renewCDD_secondRenewal_throwsException() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(300L).contractTypeCode("CDD")
            .currentStatusCode("ACTIF")
            .cddRenouvellementCount(1) // already renewed once
            .dateFinPrevue(LocalDate.now().plusDays(60))
            .paysId(1L).employeeProfile(profile).build();

        when(contractRepo.findById(300L)).thenReturn(Optional.of(contract));

        RenewCDDRequest dto = new RenewCDDRequest();
        dto.setNewDateFin(LocalDate.now().plusYears(1));

        assertThatThrownBy(() -> service.renewCDD(300L, dto, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("seul renouvellement");
    }

    // ── 13. Convert CDD to CDI — creates new CDI contract ────────────────────

    @Test
    void convertToCDI_createsNewCDIContract() throws Exception {
        EmployeeContract cdd = EmployeeContract.builder()
            .id(400L).contractTypeCode("CDD")
            .currentStatusCode("ACTIF")
            .dossierLocked(false)
            .paysId(1L).employeeProfile(profile).build();

        // First call: transitionState("CONVERSION_CDI") → second call: createContract(CDI)
        when(contractRepo.findById(400L)).thenReturn(Optional.of(cdd));
        when(stateMachine.isTransitionAllowed("CDD", "ACTIF", "CONVERSION_CDI")).thenReturn(true);
        when(contractRepo.save(any())).thenAnswer(inv -> {
            EmployeeContract c = inv.getArgument(0);
            if (c.getId() == null) c.setId(401L);
            return c;
        });
        when(profileRepo.findById(10L)).thenReturn(Optional.of(profile));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDI")).thenReturn(Optional.of(cdiConfig));

        ConvertToCDIRequest dto = new ConvertToCDIRequest();
        dto.setCdiStartDate(LocalDate.of(2026, 7, 1));

        ContractDetailDto result = service.convertToCDI(400L, dto, 1L);

        assertThat(result.getContractTypeCode()).isEqualTo("CDI");
        assertThat(result.getCurrentStatusCode()).isEqualTo("RECRUTEMENT");
    }

    // ── 14. logTransition — repo failure does not throw ──────────────────────

    @Test
    void logTransition_neverThrowsOnFailure() {
        EmployeeContract contract = EmployeeContract.builder()
            .id(500L).contractTypeCode("CDI")
            .employeeProfile(profile).build();

        when(transitionRepo.save(any())).thenThrow(new RuntimeException("DB down"));

        // Should complete without throwing
        assertThatCode(() ->
            service.logTransition(contract, "RECRUTEMENT", "PERIODE_ESSAI",
                "START_TRIAL", 1L, null, null)
        ).doesNotThrowAnyException();
    }

    // ── 15–21. LifecycleAlertJob — alerts derived from the contract dates each run ──

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final NotificationRoutingService.DispatchResult REACHED =
        new NotificationRoutingService.DispatchResult(true, 2, 0, false);
    private static final NotificationRoutingService.DispatchResult NOBODY =
        new NotificationRoutingService.DispatchResult(true, 0, 0, false);

    private LifecycleAlertJob job() {
        lenient().when(jdbc.queryForObject(anyString(), eq(String.class), eq(10L))).thenReturn("Test User");
        return new LifecycleAlertJob(contractRepo, alertRepo, configRepo, service, notificationRoutingService);
    }

    private EmployeeContract cdd(long id, LocalDate end) {
        return EmployeeContract.builder()
            .id(id).paysId(1L).contractTypeCode("CDD").currentStatusCode("ACTIF")
            .dateDebut(TODAY.minusMonths(6)).dateFinPrevue(end)
            .isActive(true).employeeProfile(profile).build();
    }

    private void onlyExpiring(EmployeeContract... contracts) {
        when(contractRepo.findExpiringContracts(TODAY, TODAY.plusDays(LifecycleAlertJob.MAX_LEAD_DAYS)))
            .thenReturn(List.of(contracts));
        when(contractRepo.findTrialPeriodsEnding(any(), any())).thenReturn(List.of());
    }

    @Test
    void job_shortCdd_insideWindowAtCreation_isAlerted() {
        // The case the old planner dropped: alert date already in the past when planned.
        onlyExpiring(cdd(600L, TODAY.plusDays(10)));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(notificationRoutingService.dispatchNow(any())).thenReturn(REACHED);

        LifecycleAlertJob.RunSummary s = job().run(TODAY);

        ArgumentCaptor<RoutingContext> ctx = ArgumentCaptor.forClass(RoutingContext.class);
        verify(notificationRoutingService).dispatchNow(ctx.capture());
        assertThat(ctx.getValue().getEventCode()).isEqualTo("CONTRACT_EXPIRY");
        assertThat(ctx.getValue().getPaysId()).isEqualTo(1L);
        assertThat(ctx.getValue().getEntityType()).isEqualTo(NotificationEntityType.EMPLOYEE_PROFILE);
        assertThat(ctx.getValue().getEntityId()).isEqualTo(10L);
        assertThat(ctx.getValue().getTemplateVars())
            .containsEntry("employeeName", "Test User")
            .containsEntry("contractType", "CDD")
            .containsEntry("targetDate", "18/10/2026")
            .containsEntry("daysLeft", "10");

        ArgumentCaptor<EmployeeLifecycleAlert> saved = ArgumentCaptor.forClass(EmployeeLifecycleAlert.class);
        verify(alertRepo).save(saved.capture());
        assertThat(saved.getValue().getIsSent()).isTrue();
        assertThat(saved.getValue().getTargetDate()).isEqualTo(TODAY.plusDays(10));
        assertThat(s.sent()).isEqualTo(1);
    }

    @Test
    void job_notYetInsideLeadTime_sendsNothing() {
        onlyExpiring(cdd(601L, TODAY.plusDays(60)));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig)); // lead 30

        job().run(TODAY);

        verify(notificationRoutingService, never()).dispatchNow(any());
        verify(alertRepo, never()).save(any());
    }

    @Test
    void job_leadTimeAbove30Days_isHonoured() {
        cddConfig.setAlertDaysBeforeExpiry(60);
        onlyExpiring(cdd(602L, TODAY.plusDays(45)));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(notificationRoutingService.dispatchNow(any())).thenReturn(REACHED);

        job().run(TODAY);

        verify(notificationRoutingService).dispatchNow(any());
    }

    @Test
    void job_nobodyReached_staysPendingForRetry() {
        onlyExpiring(cdd(603L, TODAY.plusDays(5)));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(notificationRoutingService.dispatchNow(any())).thenReturn(NOBODY);

        LifecycleAlertJob.RunSummary s = job().run(TODAY);

        ArgumentCaptor<EmployeeLifecycleAlert> saved = ArgumentCaptor.forClass(EmployeeLifecycleAlert.class);
        verify(alertRepo).save(saved.capture());
        assertThat(saved.getValue().getIsSent()).isFalse();
        assertThat(s.pending()).isEqualTo(1);
    }

    @Test
    void job_legacyRowAlreadySentForSameDate_isNotResent() {
        EmployeeContract c = cdd(604L, TODAY.plusDays(20));
        onlyExpiring(c);
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDD")).thenReturn(Optional.of(cddConfig));
        when(alertRepo.findFirstByContractIdAndAlertTypeInAndTargetDateOrderByIdAsc(
                eq(604L), anyCollection(), eq(TODAY.plusDays(20))))
            .thenReturn(Optional.of(EmployeeLifecycleAlert.builder()
                .alertType("CONTRACT_EXPIRY_30D").targetDate(TODAY.plusDays(20)).isSent(true).build()));

        job().run(TODAY);

        verify(notificationRoutingService, never()).dispatchNow(any());
    }

    @Test
    void job_trialPeriodEnding_raisesTrialEvent() {
        EmployeeContract c = EmployeeContract.builder()
            .id(605L).paysId(1L).contractTypeCode("CDI").currentStatusCode("PERIODE_ESSAI")
            .dateDebut(TODAY.minusDays(85)).dateFinPeriodeEssai(TODAY.plusDays(5))
            .isActive(true).employeeProfile(profile).build();
        when(contractRepo.findExpiringContracts(any(), any())).thenReturn(List.of());
        when(contractRepo.findTrialPeriodsEnding(any(), any())).thenReturn(List.of(c));
        when(configRepo.findByPaysIdAndContractTypeCode(1L, "CDI")).thenReturn(Optional.of(cdiConfig)); // trial lead 15
        when(notificationRoutingService.dispatchNow(any())).thenReturn(REACHED);

        job().run(TODAY);

        ArgumentCaptor<RoutingContext> ctx = ArgumentCaptor.forClass(RoutingContext.class);
        verify(notificationRoutingService).dispatchNow(ctx.capture());
        assertThat(ctx.getValue().getEventCode()).isEqualTo("TRIAL_PERIOD_END");
        assertThat(ctx.getValue().getTemplateVars()).containsEntry("daysLeft", "5");
    }

    @Test
    void job_renewedTrial_usesRenewedDate() {
        EmployeeContract c = EmployeeContract.builder()
            .dateFinPeriodeEssai(TODAY.minusDays(1))
            .periodeEssaiRenouvelee(true).dateFinPeRenouvellement(TODAY.plusDays(40)).build();

        assertThat(LifecycleAlertJob.effectiveTrialEnd(c)).isEqualTo(TODAY.plusDays(40));
    }
}
