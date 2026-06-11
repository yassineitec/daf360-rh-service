package com.daf360.rh.regime;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.service.RegimeResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegimeResolutionServiceTest {

    @Mock EmployeeProfileRepository profileRepo;
    @Mock RegimeRoleAssignmentRepository roleAssignRepo;
    @Mock WorkingTimeRegimeRepository regimeRepo;
    @Mock JdbcTemplate jdbc;

    @InjectMocks RegimeResolutionService service;

    private static final Long PROFILE_ID = 1L;
    private static final Long USER_ID    = 10L;
    private static final Long PAYS_ID    = 179L;
    private static final Long ROLE_ID    = 13L;
    private static final Long REGIME_A   = 100L;
    private static final Long REGIME_B   = 200L;

    private EmployeeProfile baseProfile;
    private WorkingTimeRegime regimeA;
    private WorkingTimeRegime regimeB;

    @BeforeEach
    void setUp() {
        baseProfile = new EmployeeProfile();
        baseProfile.setId(PROFILE_ID);
        baseProfile.setUserId(USER_ID);
        baseProfile.setPaysId(PAYS_ID);

        regimeA = WorkingTimeRegime.builder()
                .id(REGIME_A).paysId(PAYS_ID).code("STD").labelFr("Standard").labelEn("Standard")
                .hoursPerWeek(BigDecimal.valueOf(40)).daysPerWeek(5).isFlexible(false)
                .isDefault(false).isActive(true).build();

        regimeB = WorkingTimeRegime.builder()
                .id(REGIME_B).paysId(PAYS_ID).code("FLEX").labelFr("Flexible").labelEn("Flexible")
                .hoursPerWeek(BigDecimal.valueOf(35)).daysPerWeek(5).isFlexible(true)
                .isDefault(true).isActive(true).build();
    }

    @Test
    void resolveForEmployee_withPersonalOverride_returnsEmployeeLevel() {
        baseProfile.setRegimeTemplateId(REGIME_A);
        baseProfile.setRegimeStartDate(LocalDate.of(2024, 1, 1));
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(regimeRepo.findById(REGIME_A)).thenReturn(Optional.of(regimeA));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAssignmentLevel()).isEqualTo("EMPLOYEE_OVERRIDE");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
        verify(roleAssignRepo, never()).findActiveForRoleAndPays(any(), any(), any());
    }

    @Test
    void resolveForEmployee_withRoleAssignment_returnsRoleLevel() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        RegimeRoleAssignment assignment = RegimeRoleAssignment.builder()
                .id(1L).regime(regimeA).paysId(PAYS_ID)
                .effectiveFrom(LocalDate.of(2024, 1, 1)).isActive(true).build();
        when(roleAssignRepo.findActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(Optional.of(assignment));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAssignmentLevel()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
    }

    @Test
    void resolveForEmployee_withDefaultOnly_returnsDefaultLevel() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(roleAssignRepo.findActiveForRoleAndPays(any(), any(), any())).thenReturn(Optional.empty());
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID))
                .thenReturn(Optional.of(regimeB));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAssignmentLevel()).isEqualTo("DEFAULT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void resolveForEmployee_withNothing_returnsNull() {
        // roleId = null → findActiveForRoleAndPays never called (no unnecessary stubbing)
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID))
                .thenReturn(Optional.empty());

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNull();
    }

    @Test
    void resolveForEmployee_inactivePersonalRegime_fallsToRole() {
        regimeA.setIsActive(false);
        baseProfile.setRegimeTemplateId(REGIME_A);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(regimeRepo.findById(REGIME_A)).thenReturn(Optional.of(regimeA));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        RegimeRoleAssignment assignment = RegimeRoleAssignment.builder()
                .id(2L).regime(regimeB).paysId(PAYS_ID)
                .effectiveFrom(LocalDate.of(2024, 1, 1)).isActive(true).build();
        when(roleAssignRepo.findActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(Optional.of(assignment));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAssignmentLevel()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void resolveForEmployee_profileNotFound_throwsException() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForEmployee(PROFILE_ID))
                .isInstanceOf(AppException.class);
    }

    @Test
    void resolveForEmployee_overrideFromWrongPays_fallsThrough() {
        // Regime A belongs to a DIFFERENT pays (999L) but profile is 179L
        regimeA.setPaysId(999L);
        baseProfile.setRegimeTemplateId(REGIME_A);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(regimeRepo.findById(REGIME_A)).thenReturn(Optional.of(regimeA));
        // After guard falls through → role lookup → no role assignment → default
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID))
                .thenReturn(Optional.of(regimeB));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getAssignmentLevel()).isEqualTo("DEFAULT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_B);
    }
}
