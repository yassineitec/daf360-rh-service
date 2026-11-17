package com.daf360.rh.regime;

import com.daf360.rh.domain.EmployeeProfile;
import com.daf360.rh.domain.EmployeeRegimeAssignment;
import com.daf360.rh.domain.WorkingTimeRegime;
import com.daf360.rh.dto.regime.ResolvedRegimeDto;
import com.daf360.rh.exception.AppException;
import com.daf360.rh.repository.EmployeeRegimeAssignmentRepository;
import com.daf360.rh.repository.RegimeAssignmentHistoryRepository;
import com.daf360.rh.repository.WorkingTimeRegimeRepository;
import com.daf360.rh.service.EmployeeRegimeAssignmentService;
import com.daf360.rh.service.RegimeResolutionService;
import com.daf360.rh.service.ScheduleCacheNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Window rules of personal assignments (V127) and the onboarding choice. */
@ExtendWith(MockitoExtension.class)
class EmployeeRegimeAssignmentServiceTest {

    @Mock EmployeeRegimeAssignmentRepository repo;
    @Mock WorkingTimeRegimeRepository regimeRepo;
    @Mock RegimeAssignmentHistoryRepository historyRepo;
    @Mock RegimeResolutionService resolution;
    @Mock ScheduleCacheNotifier cacheNotifier;

    @InjectMocks EmployeeRegimeAssignmentService service;

    private static final Long PAYS_ID = 179L;
    private static final Long ACTOR   = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private EmployeeProfile profile;
    private WorkingTimeRegime std;
    private WorkingTimeRegime partTime;

    @BeforeEach
    void setUp() {
        profile = EmployeeProfile.builder().id(10L).userId(99L).paysId(PAYS_ID).deleted(false).build();
        std = WorkingTimeRegime.builder().id(1L).paysId(PAYS_ID).code("STD").labelFr("Standard")
                .hoursPerWeek(BigDecimal.valueOf(40)).daysPerWeek(5).isActive(true).isDefault(true).build();
        partTime = WorkingTimeRegime.builder().id(2L).paysId(PAYS_ID).code("PT").labelFr("Mi-temps")
                .hoursPerWeek(BigDecimal.valueOf(20)).daysPerWeek(5).isActive(true).isDefault(false).build();
        lenient().when(resolution.todayIn(PAYS_ID)).thenReturn(TODAY);
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private EmployeeRegimeAssignment row(long id, WorkingTimeRegime r, LocalDate from, LocalDate to, String source) {
        return EmployeeRegimeAssignment.builder().id(id).employeeProfileId(10L).regime(r)
                .effectiveFrom(from).effectiveTo(to).isActive(true).source(source).build();
    }

    // ── assign ───────────────────────────────────────────────────────────────

    @Test
    void openEnded_replacesFromItsStartDate() {
        LocalDate from = TODAY.plusDays(20);
        EmployeeRegimeAssignment current = row(1, std, LocalDate.of(2025, 1, 1), null, "MANUAL");
        EmployeeRegimeAssignment later   = row(2, std, TODAY.plusDays(40), null, "MANUAL");
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));
        when(repo.findAllActive(10L)).thenReturn(List.of(later, current));

        service.assign(profile, 2L, from, null, "Temps partiel", "MANUAL", ACTOR);

        assertThat(current.getIsActive()).isTrue();
        assertThat(current.getEffectiveTo()).isEqualTo(from.minusDays(1));
        assertThat(later.getIsActive()).isFalse();
        verify(historyRepo).save(any());
        verify(cacheNotifier).userChanged(99L);
    }

    @Test
    void bounded_isLaidOnTop_previousResumesAfterIt() {
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        service.assign(profile, 2L, TODAY.plusDays(5), TODAY.plusDays(15), "Mission", "MANUAL", ACTOR);

        verify(repo, never()).findAllActive(any());
        ArgumentCaptor<EmployeeRegimeAssignment> saved = ArgumentCaptor.forClass(EmployeeRegimeAssignment.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getEffectiveTo()).isEqualTo(TODAY.plusDays(15));
    }

    @Test
    void seasonalRegime_isRefused() {
        partTime.setSeasonalFrom(LocalDate.of(2027, 7, 20));
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        assertThatThrownBy(() -> service.assign(profile, 2L, TODAY, null, "x", "MANUAL", ACTOR))
                .isInstanceOf(AppException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void regimeOfAnotherEntity_isRefused() {
        partTime.setPaysId(1L);
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        assertThatThrownBy(() -> service.assign(profile, 2L, TODAY, null, "x", "MANUAL", ACTOR))
                .isInstanceOf(AppException.class);
    }

    @Test
    void inactiveRegime_isRefused() {
        partTime.setIsActive(false);
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        assertThatThrownBy(() -> service.assign(profile, 2L, TODAY, null, "x", "MANUAL", ACTOR))
                .isInstanceOf(AppException.class);
    }

    @Test
    void noActor_noHistoryRow_insteadOfAFailingInsert() {
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        service.assign(profile, 2L, TODAY, TODAY.plusDays(2), "x", "MANUAL", null);

        verify(historyRepo, never()).save(any());
    }

    // ── end ──────────────────────────────────────────────────────────────────

    @Test
    void end_startedRow_endsYesterday() {
        EmployeeRegimeAssignment r = row(1, partTime, LocalDate.of(2025, 1, 1), null, "MANUAL");
        when(repo.findById(1L)).thenReturn(Optional.of(r));

        service.end(profile, 1L, ACTOR);

        assertThat(r.getIsActive()).isTrue();
        assertThat(r.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void end_rowStartingToday_isCancelled() {
        EmployeeRegimeAssignment r = row(1, partTime, TODAY, null, "MANUAL");
        when(repo.findById(1L)).thenReturn(Optional.of(r));

        service.end(profile, 1L, ACTOR);

        assertThat(r.getIsActive()).isFalse();
    }

    @Test
    void end_alreadyEndedRow_isRefused() {
        EmployeeRegimeAssignment r = row(1, partTime, LocalDate.of(2025, 1, 1), TODAY.minusDays(3), "MANUAL");
        when(repo.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.end(profile, 1L, ACTOR)).isInstanceOf(AppException.class);
    }

    @Test
    void end_rowOfAnotherEmployee_isNotFound() {
        EmployeeRegimeAssignment r = row(1, partTime, LocalDate.of(2025, 1, 1), null, "MANUAL");
        r.setEmployeeProfileId(555L);
        when(repo.findById(1L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.end(profile, 1L, ACTOR)).isInstanceOf(AppException.class);
    }

    @Test
    void endAll_nothingCurrentOrUpcoming_isRefused() {
        when(repo.findCurrentAndUpcoming(10L, TODAY)).thenReturn(List.of());

        assertThatThrownBy(() -> service.endAll(profile, ACTOR)).isInstanceOf(AppException.class);
    }

    // ── onboarding ───────────────────────────────────────────────────────────

    @Test
    void onboardingChoiceEqualToInherited_storesNothingPersonal() {
        ResolvedRegimeDto inherited = new ResolvedRegimeDto();
        inherited.setRegimeId(1L);
        inherited.setAssignmentLevel("DEFAULT");
        when(regimeRepo.findById(1L)).thenReturn(Optional.of(std));
        when(resolution.resolveInherited(99L, PAYS_ID, TODAY)).thenReturn(inherited);

        service.recordOnboardingChoice(profile, 1L, TODAY, ACTOR);

        verify(repo, never()).save(any());
    }

    @Test
    void onboardingChoiceDifferentFromInherited_storesAnOnboardingRow_andReplacesAPreviousAttempt() {
        ResolvedRegimeDto inherited = new ResolvedRegimeDto();
        inherited.setRegimeId(1L);
        EmployeeRegimeAssignment previousAttempt = row(9, std, TODAY, null, "ONBOARDING");
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));
        when(repo.findByEmployeeProfileIdAndSourceAndIsActiveTrue(10L, "ONBOARDING"))
                .thenReturn(List.of(previousAttempt));
        when(resolution.resolveInherited(99L, PAYS_ID, TODAY)).thenReturn(inherited);

        service.recordOnboardingChoice(profile, 2L, TODAY, ACTOR);

        assertThat(previousAttempt.getIsActive()).isFalse();
        ArgumentCaptor<EmployeeRegimeAssignment> saved = ArgumentCaptor.forClass(EmployeeRegimeAssignment.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        EmployeeRegimeAssignment created = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(created.getSource()).isEqualTo("ONBOARDING");
        assertThat(created.getRegime().getId()).isEqualTo(2L);
        assertThat(created.getEffectiveTo()).isNull();
    }

    @Test
    void onboardingSeasonalChoice_isRefused() {
        partTime.setSeasonalFrom(LocalDate.of(2027, 7, 20));
        when(regimeRepo.findById(2L)).thenReturn(Optional.of(partTime));

        assertThatThrownBy(() -> service.recordOnboardingChoice(profile, 2L, TODAY, ACTOR))
                .isInstanceOf(AppException.class);
    }
}
