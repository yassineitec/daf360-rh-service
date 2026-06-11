package com.daf360.rh.regime;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.Role;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.WorkingTimeRegimeMapper;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.RoleRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.RegimeResolutionService;
import com.daf360.rh.service.WorkingTimeRegimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkingTimeRegimeServiceTest {

    @Mock WorkingTimeRegimeRepository    regimeRepository;
    @Mock EmployeeProfileRepository      profileRepository;
    @Mock WorkingTimeRegimeMapper        mapper;
    @Mock AuditService                   auditService;
    @Mock RegimeRoleAssignmentRepository roleAssignRepo;
    @Mock RegimeAssignmentHistoryRepository historyRepo;
    @Mock RoleRepository                 roleRepo;
    @Mock RegimeResolutionService        resolutionService;

    @InjectMocks WorkingTimeRegimeService service;

    private static final Long REGIME_ID  = 1L;
    private static final Long PROFILE_ID = 10L;
    private static final Long PAYS_ID    = 179L;
    private static final Long ROLE_ID    = 5L;

    private WorkingTimeRegime activeRegime;
    private EmployeeProfile   employeeProfile;

    @BeforeEach
    void setUp() {
        activeRegime = WorkingTimeRegime.builder()
                .id(REGIME_ID).paysId(PAYS_ID).code("STD")
                .labelFr("Standard").labelEn("Standard")
                .hoursPerWeek(BigDecimal.valueOf(40)).daysPerWeek(5)
                .isFlexible(false).isDefault(false).isActive(true)
                .build();

        employeeProfile = EmployeeProfile.builder()
                .id(PROFILE_ID).userId(99L).paysId(PAYS_ID)
                .deleted(false).build();
    }

    // ── 1. deleteRegime: used by employee → exception ────────────────────────

    @Test
    void deleteRegime_usedByEmployee_throwsException() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(profileRepository.countByRegimeTemplateIdAndDeletedFalse(REGIME_ID)).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(regimeRepository, never()).save(any());
    }

    // ── 2. deleteRegime: used by role → exception ─────────────────────────────

    @Test
    void deleteRegime_usedByRole_throwsException() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(profileRepository.countByRegimeTemplateIdAndDeletedFalse(REGIME_ID)).thenReturn(0L);
        when(roleAssignRepo.existsByRegimeIdAndIsActiveTrue(REGIME_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(regimeRepository, never()).save(any());
    }

    // ── 3. deleteRegime: is default → exception ───────────────────────────────

    @Test
    void deleteRegime_isDefault_throwsException() {
        activeRegime.setIsDefault(true);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(profileRepository.countByRegimeTemplateIdAndDeletedFalse(REGIME_ID)).thenReturn(0L);
        when(roleAssignRepo.existsByRegimeIdAndIsActiveTrue(REGIME_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(regimeRepository, never()).save(any());
    }

    // ── 4. deleteRegime: unused, non-default → soft-deletes ──────────────────

    @Test
    void deleteRegime_unused_softDeletes() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(profileRepository.countByRegimeTemplateIdAndDeletedFalse(REGIME_ID)).thenReturn(0L);
        when(roleAssignRepo.existsByRegimeIdAndIsActiveTrue(REGIME_ID)).thenReturn(false);
        when(regimeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteRegime(REGIME_ID, null);

        verify(regimeRepository).save(argThat(r -> !((WorkingTimeRegime) r).getIsActive()));
        verify(auditService).log(any(), eq("DELETE_REGIME"), eq("WorkingTimeRegime"), eq(REGIME_ID), any(), any());
    }

    // ── 5. assignRegimeToRole: deactivates previous assignment ───────────────

    @Test
    void assignRegimeToRole_deactivatesPreviousAssignment() {
        Role role = Role.builder().id(ROLE_ID).frenchName("Ingénieur").build();

        AssignRegimeToRoleRequest dto = new AssignRegimeToRoleRequest();
        dto.setRegimeId(REGIME_ID);
        dto.setRoleId(ROLE_ID);
        dto.setPaysId(PAYS_ID);
        dto.setEffectiveFrom(LocalDate.of(2026, 7, 1));

        RegimeRoleAssignment previousAssignment = RegimeRoleAssignment.builder()
                .id(99L).regime(activeRegime).role(role).paysId(PAYS_ID)
                .effectiveFrom(LocalDate.of(2025, 1, 1)).isActive(true).build();

        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(roleRepo.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(roleAssignRepo.findActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(Optional.of(previousAssignment));
        when(roleAssignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignRegimeToRole(dto, null);

        verify(roleAssignRepo).deactivateForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any());
        verify(roleAssignRepo).save(any(RegimeRoleAssignment.class));
        verify(historyRepo).save(any());
    }

    // ── 6. assignRegimeToEmployee: wrong pays → exception ────────────────────

    @Test
    void assignRegimeToEmployee_wrongPays_throwsException() {
        Long differentPaysId = 999L;
        activeRegime.setPaysId(differentPaysId); // regime belongs to different pays

        AssignRegimeToEmployeeRequest dto = new AssignRegimeToEmployeeRequest();
        dto.setRegimeId(REGIME_ID);
        dto.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        dto.setReason("Test override");

        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of((EmployeeProfile) employeeProfile));
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.assignRegimeToEmployee(PROFILE_ID, dto, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(profileRepository, never()).save(any(EmployeeProfile.class));
    }

    // ── 7. removeEmployeeOverride: no existing override → exception ───────────

    @Test
    void removeEmployeeOverride_noOverride_throwsException() {
        // profile has no regime override (regimeTemplateId = null)
        employeeProfile.setRegimeTemplateId(null);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of((EmployeeProfile) employeeProfile));

        assertThatThrownBy(() -> service.removeEmployeeOverride(PROFILE_ID, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(profileRepository, never()).save(any(EmployeeProfile.class));
    }

    // ── 8. assignRegimeToRole: effectiveTo before effectiveFrom → exception ───

    @Test
    void assignRegimeToRole_invalidDateRange_throwsException() {
        AssignRegimeToRoleRequest dto = new AssignRegimeToRoleRequest();
        dto.setRegimeId(REGIME_ID);
        dto.setRoleId(ROLE_ID);
        dto.setPaysId(PAYS_ID);
        dto.setEffectiveFrom(LocalDate.of(2026, 8, 1));
        dto.setEffectiveTo(LocalDate.of(2026, 7, 1)); // effectiveTo before effectiveFrom

        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.assignRegimeToRole(dto, null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));

        verify(roleAssignRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }
}
