package com.daf360.rh.regime;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.RegimeRoleAssignment;
import com.daf360.rh.domain.Role;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.repository.BreakTemplateRepository;
import com.daf360.rh.repository.EmployeeProfileRepository;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeRoleAssignmentRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.service.PaysTimezoneService;
import com.daf360.rh.service.PaysWeekendService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolution order: SEASONAL → personal assignment (dated rows, V127) → role → default.
 * PaysTimezoneService is mocked and returns no zone, so "today" is the JVM date.
 */
@ExtendWith(MockitoExtension.class)
class RegimeResolutionServiceTest {

    @Mock EmployeeProfileRepository profileRepo;
    @Mock RegimeRoleAssignmentRepository roleAssignRepo;
    @Mock EmployeeRegimeAssignmentRepository personalRepo;
    @Mock WorkingTimeRegimeRepository regimeRepo;
    @Mock BreakTemplateRepository breakTemplateRepo;
    @Mock PaysWeekendService weekendService;
    @Mock PaysTimezoneService timezoneService;
    @Mock JdbcTemplate jdbc;

    @InjectMocks RegimeResolutionService service;

    private static final Long PROFILE_ID = 1L;
    private static final Long USER_ID    = 10L;
    private static final Long PAYS_ID    = 179L;
    private static final Long ROLE_ID    = 13L;
    private static final Long REGIME_A   = 100L;
    private static final Long REGIME_B   = 200L;
    private static final Long REGIME_S   = 300L;

    private EmployeeProfile baseProfile;
    private WorkingTimeRegime regimeA;   // plain regime
    private WorkingTimeRegime regimeB;   // entity default
    private WorkingTimeRegime regimeS;   // seasonal

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

        regimeS = WorkingTimeRegime.builder()
                .id(REGIME_S).paysId(PAYS_ID).code("SUM").labelFr("Été").labelEn("Summer")
                .hoursPerWeek(BigDecimal.valueOf(37.5)).daysPerWeek(5).isFlexible(false)
                .isDefault(false).isActive(true)
                .seasonalFrom(LocalDate.now().minusDays(3)).seasonalTo(LocalDate.now().plusDays(11))
                .build();
    }

    private static EmployeeRegimeAssignment personal(long id, WorkingTimeRegime r, LocalDate from, LocalDate to) {
        return EmployeeRegimeAssignment.builder().id(id).employeeProfileId(PROFILE_ID)
                .regime(r).effectiveFrom(from).effectiveTo(to).isActive(true).build();
    }

    private static RegimeRoleAssignment role(long id, WorkingTimeRegime r) {
        return RegimeRoleAssignment.builder().id(id).regime(r).paysId(PAYS_ID)
                .role(Role.builder().id(ROLE_ID).build())
                .effectiveFrom(LocalDate.of(2024, 1, 1)).isActive(true).build();
    }

    // ── Level 2: personal assignment ─────────────────────────────────────────

    @Test
    void personalAssignmentCoveringToday_returnsEmployeeLevel() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any()))
                .thenReturn(List.of(personal(1, regimeA, LocalDate.of(2024, 1, 1), null)));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("EMPLOYEE_OVERRIDE");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
        verify(roleAssignRepo, never()).findAllActiveForRoleAndPays(any(), any(), any());
    }

    @Test
    void profileColumnsAreNoLongerRead() {
        // The legacy single slot only records the onboarding choice now.
        baseProfile.setRegimeTemplateId(REGIME_A);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("DEFAULT");
        verify(regimeRepo, never()).findById(any());
    }

    @Test
    void inactivePersonalRegime_fallsToRole() {
        regimeA.setIsActive(false);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any()))
                .thenReturn(List.of(personal(1, regimeA, LocalDate.of(2024, 1, 1), null)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(roleAssignRepo.findAllActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(List.of(role(2, regimeB)));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void personalRegimeOfAnotherEntity_isIgnored() {
        regimeA.setPaysId(999L);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any()))
                .thenReturn(List.of(personal(1, regimeA, LocalDate.of(2024, 1, 1), null)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        assertThat(service.resolveForEmployee(PROFILE_ID).getAssignmentLevel()).isEqualTo("DEFAULT");
    }

    @Test
    void personalSeasonalRegime_isIgnoredOutsideItsOwnWindow() {
        // A seasonal regime assigned personally used to apply all year.
        regimeS.setSeasonalFrom(LocalDate.now().plusMonths(6));
        regimeS.setSeasonalTo(LocalDate.now().plusMonths(7));
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any()))
                .thenReturn(List.of(personal(1, regimeS, LocalDate.of(2024, 1, 1), null)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        assertThat(service.resolveForEmployee(PROFILE_ID).getRegimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void stackedRows_firstUsableRowWins() {
        // Repository order = newest start first; an unusable newest row is skipped.
        WorkingTimeRegime inactive = WorkingTimeRegime.builder().id(999L).paysId(PAYS_ID)
                .isActive(false).build();
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any())).thenReturn(List.of(
                personal(2, inactive, LocalDate.now().minusDays(1), LocalDate.now().plusDays(5)),
                personal(1, regimeA, LocalDate.of(2024, 1, 1), null)));

        assertThat(service.resolveForEmployee(PROFILE_ID).getRegimeId()).isEqualTo(REGIME_A);
    }

    // ── Dated resolution ─────────────────────────────────────────────────────

    @Test
    void resolveAtPastDate_queriesEveryLevelForThatDate() {
        LocalDate past = LocalDate.of(2025, 3, 15);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(PROFILE_ID, past))
                .thenReturn(List.of(personal(1, regimeA, LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31))));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID, past);

        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
        assertThat(result.getResolvedForDate()).isEqualTo(past);
        verify(regimeRepo).findActiveSeasonalForPays(PAYS_ID, past);
    }

    // ── Levels 3 / 4 ─────────────────────────────────────────────────────────

    @Test
    void roleAssignment_returnsRoleLevel() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(roleAssignRepo.findAllActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(List.of(role(1, regimeA)));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
    }

    @Test
    void roleAssignmentOnSeasonalRegime_isSkipped() {
        regimeS.setSeasonalFrom(LocalDate.now().plusMonths(6));
        regimeS.setSeasonalTo(LocalDate.now().plusMonths(7));
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(roleAssignRepo.findAllActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(List.of(role(1, regimeS)));
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        assertThat(service.resolveForEmployee(PROFILE_ID).getAssignmentLevel()).isEqualTo("DEFAULT");
    }

    @Test
    void defaultOnly_returnsDefaultLevel() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("DEFAULT");
        assertThat(result.getRegimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void nothingConfigured_returnsNull() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertThat(service.resolveForEmployee(PROFILE_ID)).isNull();
    }

    // ── Level 1: seasonal ────────────────────────────────────────────────────

    @Test
    void activeSeasonal_beatsPersonalAssignment() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(regimeRepo.findActiveSeasonalForPays(eq(PAYS_ID), any())).thenReturn(List.of(regimeS));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getAssignmentLevel()).isEqualTo("SEASONAL");
        assertThat(result.getIsSeasonal()).isTrue();
        assertThat(result.getRegimeId()).isEqualTo(REGIME_S);
        verify(personalRepo, never()).findCovering(any(), any());
    }

    // ── Inherited (onboarding) ───────────────────────────────────────────────

    @Test
    void resolveInherited_skipsSeasonalAndPersonal() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(ROLE_ID);
        when(roleAssignRepo.findAllActiveForRoleAndPays(eq(ROLE_ID), eq(PAYS_ID), any()))
                .thenReturn(List.of(role(1, regimeA)));

        ResolvedRegimeDto result = service.resolveInherited(USER_ID, PAYS_ID, LocalDate.of(2026, 12, 1));

        assertThat(result.getRegimeId()).isEqualTo(REGIME_A);
        verify(personalRepo, never()).findCovering(any(), any());
        verify(regimeRepo, never()).findActiveSeasonalForPays(any(), any());
    }

    // ── No fabricated hours ──────────────────────────────────────────────────

    @Test
    void regimeWithoutTimes_returnsNullAliasesNotDefaults() {
        regimeA.setStartTime(null);
        regimeA.setEndTime(null);
        regimeA.setHoursPerWeek(null);
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.of(baseProfile));
        when(personalRepo.findCovering(eq(PROFILE_ID), any()))
                .thenReturn(List.of(personal(1, regimeA, LocalDate.of(2024, 1, 1), null)));

        ResolvedRegimeDto result = service.resolveForEmployee(PROFILE_ID);

        assertThat(result.getHeureDebut()).isNull();
        assertThat(result.getHeureFin()).isNull();
        assertThat(result.getHeuresJour()).isNull();
    }

    // ── By-user resolution (the pointage entry point) ─────────────────────────

    @Test
    void resolveForUser_looksUpProfileByUserId() {
        when(profileRepo.findByUserId(USER_ID)).thenReturn(Optional.of(baseProfile));
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(USER_ID))).thenReturn(null);

        assertThat(service.resolveForUser(USER_ID).getRegimeId()).isEqualTo(REGIME_B);
        verify(profileRepo, never()).findById(any());
    }

    @Test
    void resolveForUser_noProfile_throwsException() {
        when(profileRepo.findByUserId(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForUser(USER_ID)).isInstanceOf(AppException.class);
    }

    @Test
    void resolveForEmployee_profileNotFound_throwsException() {
        when(profileRepo.findById(PROFILE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveForEmployee(PROFILE_ID)).isInstanceOf(AppException.class);
    }

    // ── Batch (overview) ─────────────────────────────────────────────────────

    @Test
    void resolveLevelsForPays_appliesTheSameOrder() {
        EmployeeProfile withPersonal = EmployeeProfile.builder().id(1L).userId(11L).paysId(PAYS_ID).build();
        EmployeeProfile withRole     = EmployeeProfile.builder().id(2L).userId(12L).paysId(PAYS_ID).build();
        EmployeeProfile withDefault  = EmployeeProfile.builder().id(3L).userId(13L).paysId(PAYS_ID).build();

        EmployeeRegimeAssignment pa = EmployeeRegimeAssignment.builder().id(1L).employeeProfileId(1L)
                .regime(regimeA).effectiveFrom(LocalDate.of(2024, 1, 1)).isActive(true).build();
        when(personalRepo.findCoveringForPays(eq(PAYS_ID), any())).thenReturn(List.of(pa));
        when(roleAssignRepo.findCoveringForPays(eq(PAYS_ID), any())).thenReturn(List.of(role(5, regimeA)));
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.of(regimeB));

        Map<Long, RegimeResolutionService.ResolvedLevel> out = service.resolveLevelsForPays(
                PAYS_ID, List.of(withPersonal, withRole, withDefault),
                Map.of(11L, 99L, 12L, ROLE_ID, 13L, 98L));

        assertThat(out.get(1L).level()).isEqualTo("EMPLOYEE_OVERRIDE");
        assertThat(out.get(1L).personalRegimeId()).isEqualTo(REGIME_A);
        assertThat(out.get(2L).level()).isEqualTo("ROLE_ASSIGNMENT");
        assertThat(out.get(3L).level()).isEqualTo("DEFAULT");
        assertThat(out.get(3L).regimeId()).isEqualTo(REGIME_B);
    }

    @Test
    void resolveLevelsForPays_seasonalWins_butKeepsThePersonalId() {
        EmployeeProfile p = EmployeeProfile.builder().id(1L).userId(11L).paysId(PAYS_ID).build();
        when(regimeRepo.findActiveSeasonalForPays(eq(PAYS_ID), any())).thenReturn(List.of(regimeS));
        when(personalRepo.findCoveringForPays(eq(PAYS_ID), any())).thenReturn(List.of(
                EmployeeRegimeAssignment.builder().id(1L).employeeProfileId(1L).regime(regimeA)
                        .effectiveFrom(LocalDate.of(2024, 1, 1)).isActive(true).build()));

        Map<Long, RegimeResolutionService.ResolvedLevel> out =
                service.resolveLevelsForPays(PAYS_ID, List.of(p), Map.of());

        assertThat(out.get(1L).level()).isEqualTo("SEASONAL");
        assertThat(out.get(1L).personalRegimeId()).isEqualTo(REGIME_A);
    }

    @Test
    void resolveLevelsForPays_nothing_leavesTheProfileOut() {
        EmployeeProfile p = EmployeeProfile.builder().id(1L).userId(11L).paysId(PAYS_ID).build();
        when(regimeRepo.findFirstByPaysIdAndIsDefaultTrueAndIsActiveTrue(PAYS_ID)).thenReturn(Optional.empty());

        assertThat(service.resolveLevelsForPays(PAYS_ID, List.of(p), Map.of())).isEmpty();
    }
}
