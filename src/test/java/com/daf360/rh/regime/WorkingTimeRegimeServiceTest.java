package com.daf360.rh.regime;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.Role;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.AssignRegimeToEmployeeRequest;
import com.daf360.rh.dto.regime.AssignRegimeToRoleRequest;
import com.daf360.rh.dto.regime.WorkingTimeRegimeCreateDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.exception.ErrorCode;
import com.daf360.rh.mapper.WorkingTimeRegimeMapper;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.RoleRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.security.TenantService;
import com.daf360.rh.service.AuditService;
import com.daf360.rh.service.EmployeeRegimeAssignmentService;
import com.daf360.rh.service.RegimeResolutionService;
import com.daf360.rh.service.ScheduleCacheNotifier;
import com.daf360.rh.service.WorkingTimeRegimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
    @Mock EmployeeRegimeAssignmentRepository personalRepo;
    @Mock EmployeeRegimeAssignmentService personalService;
    @Mock RegimeAssignmentHistoryRepository historyRepo;
    @Mock RoleRepository                 roleRepo;
    @Mock RegimeResolutionService        resolutionService;
    @Mock ScheduleCacheNotifier          cacheNotifier;
    @Mock TenantService                  tenantService;
    @Mock JdbcTemplate                   jdbc;

    @InjectMocks WorkingTimeRegimeService service;

    private static final Long REGIME_ID  = 1L;
    private static final Long PROFILE_ID = 10L;
    private static final Long PAYS_ID    = 179L;
    private static final Long ROLE_ID    = 5L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private WorkingTimeRegime activeRegime;
    private EmployeeProfile   employeeProfile;
    private Role              role;
    private Authentication    hr;

    @BeforeEach
    void setUp() {
        activeRegime = WorkingTimeRegime.builder()
                .id(REGIME_ID).paysId(PAYS_ID).code("STD")
                .labelFr("Standard").labelEn("Standard")
                .hoursPerWeek(BigDecimal.valueOf(40)).daysPerWeek(5)
                .isFlexible(false).isDefault(false).isActive(true)
                .build();
        employeeProfile = EmployeeProfile.builder()
                .id(PROFILE_ID).userId(99L).paysId(PAYS_ID).deleted(false).build();
        role = Role.builder().id(ROLE_ID).frenchName("Ingénieur").build();
        hr = new UsernamePasswordAuthenticationToken("7", null,
                List.of(new SimpleGrantedAuthority("ADMIN_REGIMES")));
        lenient().when(resolutionService.todayIn(any())).thenReturn(TODAY);
    }

    private AssignRegimeToRoleRequest roleRequest(LocalDate from, LocalDate to) {
        AssignRegimeToRoleRequest dto = new AssignRegimeToRoleRequest();
        dto.setRegimeId(REGIME_ID);
        dto.setRoleId(ROLE_ID);
        dto.setPaysId(PAYS_ID);
        dto.setEffectiveFrom(from);
        dto.setEffectiveTo(to);
        return dto;
    }

    private RegimeRoleAssignment roleRow(long id, LocalDate from, LocalDate to) {
        return RegimeRoleAssignment.builder().id(id).regime(activeRegime).role(role).paysId(PAYS_ID)
                .effectiveFrom(from).effectiveTo(to).isActive(true).build();
    }

    // ── deleteRegime ─────────────────────────────────────────────────────────

    @Test
    void deleteRegime_usedByEmployee_throwsException() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(personalRepo.countEmployeesUsingFrom(REGIME_ID, TODAY)).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, hr))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
        verify(regimeRepository, never()).save(any());
    }

    @Test
    void deleteRegime_usedByRole_throwsException() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(roleAssignRepo.countUsingRegimeFrom(REGIME_ID, TODAY)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, hr)).isInstanceOf(AppException.class);
        verify(regimeRepository, never()).save(any());
    }

    @Test
    void deleteRegime_isDefault_throwsException() {
        activeRegime.setIsDefault(true);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.deleteRegime(REGIME_ID, hr)).isInstanceOf(AppException.class);
        verify(regimeRepository, never()).save(any());
    }

    @Test
    void deleteRegime_unused_softDeletes_andEvictsPointageCache() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(regimeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteRegime(REGIME_ID, hr);

        verify(regimeRepository).save(argThat(r -> !((WorkingTimeRegime) r).getIsActive()));
        verify(cacheNotifier).everyoneChanged();
    }

    @Test
    void legacyDeactivate_goesThroughTheSameChecks() {
        activeRegime.setIsDefault(true);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.deactivate(REGIME_ID, hr)).isInstanceOf(AppException.class);
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    void create_asDefault_clearsTheOtherDefaults() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setPaysId(PAYS_ID);
        activeRegime.setIsDefault(true);
        when(mapper.toEntity(dto)).thenReturn(activeRegime);
        when(regimeRepository.save(any())).thenReturn(activeRegime);

        service.create(dto, hr);

        // Before the insert: UX_WTR_OneDefaultPerPays is checked when the IDENTITY insert runs.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(regimeRepository);
        order.verify(regimeRepository).clearDefaultForPays(PAYS_ID, null);
        order.verify(regimeRepository).save(activeRegime);
    }

    @Test
    void update_becomingDefault_clearsTheOthersBeforeSaving() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setIsDefault(true);
        activeRegime.setIsDefault(false);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        org.mockito.Mockito.doAnswer(inv -> { activeRegime.setIsDefault(true); return null; })
            .when(mapper).updateFromDto(dto, activeRegime);
        when(regimeRepository.save(any())).thenReturn(activeRegime);

        service.update(REGIME_ID, dto, hr);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(regimeRepository);
        order.verify(regimeRepository).clearDefaultForPays(PAYS_ID, REGIME_ID);
        order.verify(regimeRepository).save(activeRegime);
    }

    @Test
    void update_alreadyDefault_doesNotClear() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setIsDefault(true);
        activeRegime.setIsDefault(true);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(regimeRepository.save(any())).thenReturn(activeRegime);

        service.update(REGIME_ID, dto, hr);

        verify(regimeRepository, org.mockito.Mockito.never()).clearDefaultForPays(any(), any());
    }

    @Test
    void create_seasonalDefault_isRefused() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setPaysId(PAYS_ID);
        activeRegime.setIsDefault(true);
        activeRegime.setSeasonalFrom(LocalDate.of(2027, 7, 20));
        when(mapper.toEntity(dto)).thenReturn(activeRegime);

        assertThatThrownBy(() -> service.create(dto, hr)).isInstanceOf(AppException.class);
        verify(regimeRepository, never()).save(any());
    }

    @Test
    void update_makingAnAssignedRegimeSeasonal_isRefused() {
        WorkingTimeRegimeCreateDto dto = new WorkingTimeRegimeCreateDto();
        dto.setSeasonalFrom(LocalDate.of(2027, 7, 20));
        dto.setSeasonalTo(LocalDate.of(2027, 8, 14));
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(personalRepo.countEmployeesUsingFrom(REGIME_ID, TODAY)).thenReturn(3L);

        assertThatThrownBy(() -> service.update(REGIME_ID, dto, hr)).isInstanceOf(AppException.class);
        verify(regimeRepository, never()).save(any());
    }

    // ── Role assignment windows ──────────────────────────────────────────────

    @Test
    void assignRegimeToRole_futureOpenEnded_endsCurrentRowWithoutGap() {
        // The bug: the current row was set is_active = 0 at once, so the role fell to the
        // default until the new date. Now it keeps applying until the day before.
        LocalDate from = TODAY.plusDays(21);
        RegimeRoleAssignment current = roleRow(1, LocalDate.of(2025, 1, 1), null);
        RegimeRoleAssignment alreadyScheduled = roleRow(2, TODAY.plusDays(30), null);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(roleRepo.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(roleAssignRepo.findAllNonCancelledForRoleAndPays(ROLE_ID, PAYS_ID))
                .thenReturn(List.of(current, alreadyScheduled));
        when(roleAssignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignRegimeToRole(roleRequest(from, null), hr);

        assertThat(current.getIsActive()).isTrue();
        assertThat(current.getEffectiveTo()).isEqualTo(from.minusDays(1));
        assertThat(alreadyScheduled.getIsActive()).isFalse();
        verify(historyRepo).save(any());
        verify(cacheNotifier).everyoneChanged();
    }

    @Test
    void assignRegimeToRole_bounded_layersOnTopWithoutTouchingOthers() {
        RegimeRoleAssignment current = roleRow(1, LocalDate.of(2025, 1, 1), null);
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));
        when(roleRepo.findById(ROLE_ID)).thenReturn(Optional.of(role));
        when(roleAssignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignRegimeToRole(roleRequest(TODAY.plusDays(5), TODAY.plusDays(20)), hr);

        assertThat(current.getIsActive()).isTrue();
        assertThat(current.getEffectiveTo()).isNull();
        verify(roleAssignRepo, never()).findAllNonCancelledForRoleAndPays(any(), any());
    }

    @Test
    void assignRegimeToRole_seasonalRegime_isRefused() {
        activeRegime.setSeasonalFrom(LocalDate.of(2027, 7, 20));
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.assignRegimeToRole(roleRequest(TODAY, null), hr))
                .isInstanceOf(AppException.class);
        verify(roleAssignRepo, never()).save(any());
    }

    @Test
    void assignRegimeToRole_invalidDateRange_throwsException() {
        when(regimeRepository.findById(REGIME_ID)).thenReturn(Optional.of(activeRegime));

        assertThatThrownBy(() -> service.assignRegimeToRole(
                roleRequest(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1)), hr))
                .isInstanceOf(AppException.class);
        verify(roleAssignRepo, never()).save(any());
        verify(historyRepo, never()).save(any());
    }

    @Test
    void removeRoleAssignment_started_endsYesterday() {
        RegimeRoleAssignment row = roleRow(1, LocalDate.of(2025, 1, 1), null);
        when(roleAssignRepo.findById(1L)).thenReturn(Optional.of(row));

        service.removeRoleAssignment(1L, hr);

        assertThat(row.getIsActive()).isTrue();
        assertThat(row.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void removeRoleAssignment_scheduled_isCancelled() {
        RegimeRoleAssignment row = roleRow(1, TODAY.plusDays(3), null);
        when(roleAssignRepo.findById(1L)).thenReturn(Optional.of(row));

        service.removeRoleAssignment(1L, hr);

        assertThat(row.getIsActive()).isFalse();
    }

    @Test
    void removeRoleAssignment_alreadyEnded_isRefused() {
        RegimeRoleAssignment row = roleRow(1, LocalDate.of(2025, 1, 1), TODAY.minusDays(10));
        when(roleAssignRepo.findById(1L)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.removeRoleAssignment(1L, hr)).isInstanceOf(AppException.class);
    }

    // ── Employee assignments ─────────────────────────────────────────────────

    @Test
    void assignRegimeToEmployee_delegatesToTheDatedService() {
        AssignRegimeToEmployeeRequest dto = new AssignRegimeToEmployeeRequest();
        dto.setRegimeId(REGIME_ID);
        dto.setEffectiveFrom(TODAY);
        dto.setReason("Temps partiel");
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(employeeProfile));
        when(personalService.assign(employeeProfile, REGIME_ID, TODAY, null, "Temps partiel",
                EmployeeRegimeAssignment.SOURCE_MANUAL, 7L))
                .thenReturn(EmployeeRegimeAssignment.builder().id(55L).build());

        service.assignRegimeToEmployee(PROFILE_ID, dto, hr);

        verify(personalService).assign(employeeProfile, REGIME_ID, TODAY, null, "Temps partiel",
                EmployeeRegimeAssignment.SOURCE_MANUAL, 7L);
    }

    // ── Read access ──────────────────────────────────────────────────────────

    @Test
    void readUserRegime_self_isAllowed() {
        Authentication self = new UsernamePasswordAuthenticationToken("99", null, List.of());
        service.assertCanReadUserRegime(99L, self);
    }

    @Test
    void readUserRegime_otherUserWithoutHrAuthority_isForbidden() {
        Authentication someone = new UsernamePasswordAuthenticationToken("50", null, List.of());

        assertThatThrownBy(() -> service.assertCanReadUserRegime(99L, someone))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void readUserRegime_hrOutsideItsScope_isForbidden() {
        when(profileRepository.findByUserId(99L)).thenReturn(Optional.of(employeeProfile));
        when(tenantService.getPaysScope()).thenReturn(
                new com.daf360.rh.security.PaysScopeContext.Scope(false, java.util.Set.of(1L)));

        assertThatThrownBy(() -> service.assertCanReadUserRegime(99L, hr)).isInstanceOf(AppException.class);
    }
}
