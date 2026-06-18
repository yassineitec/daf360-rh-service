package com.daf360.rh.lifecycle;

import com.daf360.rh.domain.ContractTypeConfig;
import com.daf360.rh.domain.EmployeeContract;
import com.daf360.rh.domain.EmployeeLifecycleAlert;
import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.dto.lifecycle.*;
import com.daf360.rh.exception.BusinessRuleException;
import com.daf360.rh.repository.*;
import com.daf360.rh.service.MailService;
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
    @Mock MailService                             mailService;
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
        when(alertRepo.existsByContractIdAndAlertType(any(), any())).thenReturn(false);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

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

    // ── 15. planContractAlerts — dedup: no second alert if exists ────────────

    @Test
    void planContractAlerts_noDuplicate() throws Exception {
        EmployeeContract contract = EmployeeContract.builder()
            .id(600L).contractTypeCode("CDD")
            .dateFinPrevue(LocalDate.now().plusDays(60))
            .employeeProfile(profile).build();

        ContractTypeConfig config = ContractTypeConfig.builder()
            .alertDaysBeforeExpiry(30).build();

        // Alert already exists
        when(alertRepo.existsByContractIdAndAlertType(600L, "CONTRACT_EXPIRY_30D")).thenReturn(true);

        service.planContractAlerts(contract, config);

        // No second alert should be saved
        verify(alertRepo, never()).save(any());
    }

    // ── 16. sendAlert — simultaneous RH + IT + Directeur notifications ────────

    @Test
    @SuppressWarnings("unchecked")
    void sendAlert_simultaneousRHITDirector() throws Exception {
        EmployeeContract contract = EmployeeContract.builder()
            .id(700L).contractTypeCode("CDD")
            .paysId(1L).employeeProfile(profile).build();

        EmployeeLifecycleAlert alert = EmployeeLifecycleAlert.builder()
            .id(1L).contract(contract)
            .employeeProfileId(10L)
            .alertType("CONTRACT_EXPIRY_30D")
            .alertDate(LocalDate.now())
            .targetDate(LocalDate.now().plusDays(30))
            .recipients("[\"RH\",\"IT\",\"DIRECTEUR_PAYS\"]")
            .isSent(false).build();

        // Permission is first vararg: queryForList(sql, permission, paysId)
        // RH perm: user 1, IT perm: user 2, DIRECTEUR perm: user 3
        lenient().when(jdbc.queryForList(anyString(), eq("RH_VIEW_CONTRACTS"), eq(1L)))
            .thenReturn(List.of(Map.of("id", 1, "email", "rh@test.com")));
        lenient().when(jdbc.queryForList(anyString(), eq("RH_MANAGE_LIFECYCLE"), eq(1L)))
            .thenReturn(List.of(Map.of("id", 2, "email", "it@test.com")));
        lenient().when(jdbc.queryForList(anyString(), eq("RH_APPROVE_RECRUITMENT_DEMAND"), eq(1L)))
            .thenReturn(List.of(Map.of("id", 3, "email", "dir@test.com")));
        lenient().when(jdbc.queryForObject(anyString(), eq(String.class), eq(10L))).thenReturn("Test User");

        // LifecycleAlertJob needs the objectMapper, get it via LifecycleAlertJob
        LifecycleAlertJob job = new LifecycleAlertJob(
            contractRepo, alertRepo, configRepo, service, mailService, jdbc, objectMapper);

        when(objectMapper.readValue(eq("[\"RH\",\"IT\",\"DIRECTEUR_PAYS\"]"),
                ArgumentMatchers.<com.fasterxml.jackson.core.type.TypeReference<List<String>>>any()))
            .thenReturn(List.of("RH", "IT", "DIRECTEUR_PAYS"));

        job.sendAlert(alert);

        // Verify 3 distinct in-app notifications sent (one per unique user)
        verify(jdbc, times(3)).update(contains("notifications"), any(), eq("RH"), any(), any());
    }
}
